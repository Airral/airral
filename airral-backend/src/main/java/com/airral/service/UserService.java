package com.airral.service;

import com.airral.domain.User;
import com.airral.domain.UserInvitation;
import com.airral.domain.enums.UserRole;
import com.airral.dto.request.InviteUserRequest;
import com.airral.dto.request.UpdateUserRequest;
import com.airral.dto.response.InvitationPreviewResponse;
import com.airral.dto.response.InvitationResponse;
import com.airral.dto.response.UserResponse;
import com.airral.repository.DepartmentRepository;
import com.airral.repository.OrganizationRepository;
import com.airral.repository.UserInvitationRepository;
import com.airral.exception.BadRequestException;
import com.airral.exception.ConflictException;
import com.airral.exception.InvitationRateLimitedException;
import com.airral.exception.NotFoundException;
import com.airral.repository.UserRepository;
import com.airral.security.LoginThrottle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class UserService {

    /**
     * Roles an invitation may give. ADMIN is AIRRAL's own staff role, and an
     * APPLICANT belongs to no company, so neither can come from a company.
     */
    static final Set<UserRole> INVITABLE_ROLES =
            EnumSet.of(UserRole.HR_MANAGER, UserRole.MANAGER, UserRole.EMPLOYEE);

    static final String INVITATION_GONE =
            "This invitation has expired, was cancelled or was already used. Ask your company to send a new one.";

    private static final int INVITATION_DAYS = 7;
    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final UserInvitationRepository invitationRepository;
    private final OrganizationRepository organizationRepository;
    private final DepartmentRepository departmentRepository;
    private final FirebaseEmailLinkSender linkSender;
    private final LoginThrottle loginThrottle;

    public UserService(UserRepository userRepository,
                      UserInvitationRepository invitationRepository,
                      OrganizationRepository organizationRepository,
                      DepartmentRepository departmentRepository,
                      FirebaseEmailLinkSender linkSender,
                      LoginThrottle loginThrottle) {
        this.userRepository = userRepository;
        this.invitationRepository = invitationRepository;
        this.organizationRepository = organizationRepository;
        this.departmentRepository = departmentRepository;
        this.linkSender = linkSender;
        this.loginThrottle = loginThrottle;
    }

    /**
     * Get all users for an organization
     */
    public Flux<UserResponse> getAllUsers(Long organizationId) {
        return userRepository.findByOrganizationId(organizationId)
                .flatMap(this::toUserResponse);
    }

    /**
     * Get user by ID
     */
    public Mono<UserResponse> getUserById(Long id, Long organizationId) {
        return userRepository.findById(id)
                .filter(user -> sameCompany(user, organizationId))
                .switchIfEmpty(Mono.error(new NotFoundException("User not found")))
                .flatMap(this::toUserResponse);
    }

    /**
     * Get team members for a manager, inside the caller's own company
     */
    public Flux<UserResponse> getTeamMembers(Long managerId, Long organizationId) {
        return userRepository.findByManagerId(managerId)
                .filter(user -> sameCompany(user, organizationId))
                .flatMap(this::toUserResponse);
    }

    /**
     * Update user profile
     *
     * <p>HR edits anyone in its own company. Everyone else edits only their own
     * profile, and only HR moves someone to another manager or department, which
     * must be in the same company.
     */
    public Mono<UserResponse> updateUser(Long id, UpdateUserRequest request, Long organizationId,
                                         Long callerId, String callerRole) {
        boolean hr = isHr(callerRole);
        if (!hr && !id.equals(callerId)) {
            return Mono.error(new AccessDeniedException("You can only edit your own profile"));
        }
        if (!hr && (request.getManagerId() != null || request.getDepartmentId() != null)) {
            return Mono.error(new AccessDeniedException("Only HR can change someone's manager or department"));
        }

        return userRepository.findById(id)
                .filter(user -> sameCompany(user, organizationId))
                .switchIfEmpty(Mono.error(new NotFoundException("User not found")))
                .flatMap(user -> checkManager(request.getManagerId(), user, organizationId)
                        .then(checkDepartment(request.getDepartmentId(), organizationId))
                        .thenReturn(user))
                .flatMap(user -> {
                    if (request.getFirstName() != null) user.setFirstName(request.getFirstName());
                    if (request.getLastName() != null) user.setLastName(request.getLastName());
                    if (request.getPhone() != null) user.setPhone(request.getPhone());
                    if (request.getDepartment() != null) user.setDepartment(request.getDepartment());
                    if (request.getJobTitle() != null) user.setJobTitle(request.getJobTitle());
                    if (request.getDepartmentId() != null) user.setDepartmentId(request.getDepartmentId());
                    if (request.getManagerId() != null) user.setManagerId(request.getManagerId());
                    user.setUpdatedAt(LocalDateTime.now());

                    return userRepository.save(user);
                })
                .flatMap(this::toUserResponse);
    }

    /**
     * Invite a new team member
     */
    public Mono<InvitationResponse> inviteUser(InviteUserRequest request, Long organizationId, Long invitedById) {
        if (!INVITABLE_ROLES.contains(request.getRole())) {
            return Mono.error(new BadRequestException(
                    "An invitation can only make someone an HR manager, a manager or an employee"));
        }
        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);

        // Check if user already exists
        return userRepository.findByEmail(email)
                .flatMap(existingUser -> {
                    if (existingUser.getOrganizationId() == null) {
                        return Mono.<UserInvitation>error(new ConflictException(
                                "That address already has an AIRRAL applicant account, so it can't be invited"));
                    }
                    if (existingUser.getOrganizationId().equals(organizationId)) {
                        return Mono.<UserInvitation>error(new ConflictException("User already exists in this organization"));
                    }
                    return Mono.<UserInvitation>error(new ConflictException("User already exists in another organization"));
                })
                .switchIfEmpty(
                    // Check if invitation already exists
                    invitationRepository.findValidInvitationByEmailAndOrganization(email, organizationId)
                            .flatMap(existing -> Mono.<UserInvitation>error(new ConflictException("Invitation already sent")))
                            .switchIfEmpty(
                                // Create new invitation, within the company's email budget
                                Mono.defer(() -> withinInvitationBudget(organizationId)).then(Mono.defer(() -> {
                                    String token = UUID.randomUUID().toString();
                                    UserInvitation invitation = UserInvitation.builder()
                                            .invitedById(invitedById)
                                            .organizationId(organizationId)
                                            .email(email)
                                            .role(request.getRole())
                                            .departmentId(request.getDepartmentId())
                                            .firstName(request.getFirstName())
                                            .lastName(request.getLastName())
                                            .department(request.getDepartment())
                                            .invitationToken(token)
                                            .expiresAt(LocalDateTime.now().plusDays(INVITATION_DAYS))
                                            .isAccepted(false)
                                            .createdAt(LocalDateTime.now())
                                            .build();

                                    return invitationRepository.save(invitation);
                                }))
                            )
                )
                .flatMap(this::sendInvitationEmail);
    }

    /**
     * Get pending invitations
     */
    public Flux<InvitationResponse> getPendingInvitations(Long organizationId) {
        return invitationRepository.findPendingByOrganizationId(organizationId)
                .map(invitation -> toInvitationResponse(invitation, null));
    }

    /**
     * Send a pending invitation's email again, with a fresh week to accept it
     */
    public Mono<InvitationResponse> resendInvitation(Long invitationId, Long organizationId) {
        return pendingInvitation(invitationId, organizationId)
                .flatMap(invitation -> withinInvitationBudget(organizationId).then(Mono.defer(() -> {
                    invitation.setExpiresAt(LocalDateTime.now().plusDays(INVITATION_DAYS));
                    return invitationRepository.save(invitation);
                })))
                .flatMap(this::sendInvitationEmail);
    }

    /**
     * Withdraw a pending invitation, so its link stops working
     */
    public Mono<Void> cancelInvitation(Long invitationId, Long organizationId) {
        return pendingInvitation(invitationId, organizationId)
                .flatMap(invitationRepository::delete);
    }

    /**
     * What the accept page shows for an invitation that can still be accepted.
     * Public: anyone holding the link's token may ask, and the answer names only
     * the address it was sent to and the company.
     */
    public Mono<InvitationPreviewResponse> describeInvitation(String invitationToken) {
        return invitationRepository.findByInvitationToken(invitationToken)
                .filter(UserService::isOpen)
                .switchIfEmpty(Mono.error(new NotFoundException(INVITATION_GONE)))
                .flatMap(invitation -> organizationRepository.findById(invitation.getOrganizationId())
                        .map(org -> InvitationPreviewResponse.builder()
                                .email(invitation.getEmail())
                                .companyName(org.getName())
                                .role(invitation.getRole())
                                .firstName(invitation.getFirstName())
                                .lastName(invitation.getLastName())
                                .build()));
    }

    /** Whether an invitation can still be accepted. */
    static boolean isOpen(UserInvitation invitation) {
        return !Boolean.TRUE.equals(invitation.getIsAccepted())
                && invitation.getExpiresAt() != null
                && invitation.getExpiresAt().isAfter(LocalDateTime.now());
    }

    private Mono<UserInvitation> pendingInvitation(Long invitationId, Long organizationId) {
        return invitationRepository.findById(invitationId)
                .filter(invitation -> organizationId != null
                        && organizationId.equals(invitation.getOrganizationId())
                        && !Boolean.TRUE.equals(invitation.getIsAccepted()))
                .switchIfEmpty(Mono.error(new NotFoundException("Invitation not found")));
    }

    private Mono<Void> withinInvitationBudget(Long organizationId) {
        return loginThrottle.invitationEmailAllowed(organizationId)
                .flatMap(allowed -> allowed
                        ? loginThrottle.recordInvitationEmail(organizationId)
                        : Mono.<Void>error(new InvitationRateLimitedException()));
    }

    /**
     * Emails the invitation's link. The invitation is kept even when the email
     * does not go out, and the response says so, so HR can press Resend rather
     * than being told the invitation already exists.
     */
    private Mono<InvitationResponse> sendInvitationEmail(UserInvitation invitation) {
        return linkSender.sendInvitation(invitation.getId(), invitation.getEmail(), invitation.getInvitationToken())
                .thenReturn(toInvitationResponse(invitation, true))
                .onErrorResume(error -> {
                    log.warn("Invitation {} saved but its email was not sent: {}", invitation.getId(), error.getMessage());
                    return Mono.just(toInvitationResponse(invitation, false));
                });
    }

    private static InvitationResponse toInvitationResponse(UserInvitation invitation, Boolean emailSent) {
        return InvitationResponse.builder()
                .id(invitation.getId())
                .email(invitation.getEmail())
                .role(invitation.getRole())
                .firstName(invitation.getFirstName())
                .lastName(invitation.getLastName())
                .department(invitation.getDepartment())
                .expiresAt(invitation.getExpiresAt())
                .createdAt(invitation.getCreatedAt())
                .emailSent(emailSent)
                .build();
    }

    /** Whether a user belongs to the given company. No company matches nobody. */
    private static boolean sameCompany(User user, Long organizationId) {
        return organizationId != null && organizationId.equals(user.getOrganizationId());
    }

    private static boolean isHr(String role) {
        return UserRole.HR_MANAGER.name().equals(role) || UserRole.ADMIN.name().equals(role);
    }

    private Mono<Void> checkManager(Long managerId, User user, Long organizationId) {
        if (managerId == null) {
            return Mono.empty();
        }
        if (managerId.equals(user.getId())) {
            return Mono.error(new BadRequestException("Someone can't be their own manager"));
        }
        return userRepository.findById(managerId)
                .filter(manager -> sameCompany(manager, organizationId))
                .switchIfEmpty(Mono.error(new BadRequestException("The manager must be someone in your company")))
                .then();
    }

    private Mono<Void> checkDepartment(Long departmentId, Long organizationId) {
        if (departmentId == null) {
            return Mono.empty();
        }
        return departmentRepository.findByIdAndOrganizationId(departmentId, organizationId)
                .switchIfEmpty(Mono.error(new BadRequestException("The department must be one of your company's")))
                .then();
    }

    /**
     * Convert User to UserResponse DTO
     */
    private Mono<UserResponse> toUserResponse(User user) {
        // Mono.just(null) throws, and nothing sets a manager yet, so a missing
        // company or manager travels as an empty Optional instead.
        Mono<Optional<String>> orgNameMono = user.getOrganizationId() != null ?
                organizationRepository.findById(user.getOrganizationId())
                        .map(org -> Optional.ofNullable(org.getName()))
                        .defaultIfEmpty(Optional.of("Unknown")) :
                Mono.just(Optional.empty());

        Mono<Optional<String>> managerNameMono = user.getManagerId() != null ?
                userRepository.findById(user.getManagerId())
                        .map(manager -> Optional.ofNullable(manager.getFullName()))
                        .defaultIfEmpty(Optional.of("Unknown")) :
                Mono.just(Optional.empty());

        return Mono.zip(orgNameMono, managerNameMono)
                .map(tuple -> UserResponse.builder()
                        .id(user.getId())
                        .email(user.getEmail())
                        .firstName(user.getFirstName())
                        .lastName(user.getLastName())
                        .phone(user.getPhone())
                        .organizationId(user.getOrganizationId())
                        .organizationName(tuple.getT1().orElse(null))
                        .role(user.getRole())
                        .isPlatformAdmin(user.getIsPlatformAdmin())
                        .managerId(user.getManagerId())
                        .managerName(tuple.getT2().orElse(null))
                        .department(user.getDepartment())
                        .jobTitle(user.getJobTitle())
                        .departmentId(user.getDepartmentId())
                        .isActive(user.getIsActive())
                        .emailVerified(user.getEmailVerified())
                        .createdAt(user.getCreatedAt())
                        .lastLoginAt(user.getLastLoginAt())
                        .build()
                );
    }
}
