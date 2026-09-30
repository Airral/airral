package com.airral.service;

import com.airral.domain.Department;
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
import com.airral.repository.JobRepository;
import com.airral.security.LoginThrottle;
import com.airral.security.TokenVersionCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
    private final TokenVersionCache tokenVersionCache;
    private final JobRepository jobRepository;

    public UserService(UserRepository userRepository,
                      UserInvitationRepository invitationRepository,
                      OrganizationRepository organizationRepository,
                      DepartmentRepository departmentRepository,
                      FirebaseEmailLinkSender linkSender,
                      LoginThrottle loginThrottle,
                      TokenVersionCache tokenVersionCache,
                      JobRepository jobRepository) {
        this.userRepository = userRepository;
        this.invitationRepository = invitationRepository;
        this.organizationRepository = organizationRepository;
        this.departmentRepository = departmentRepository;
        this.linkSender = linkSender;
        this.loginThrottle = loginThrottle;
        this.tokenVersionCache = tokenVersionCache;
        this.jobRepository = jobRepository;
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
        if (!hr && (request.getManagerId() != null || request.getDepartmentId() != null
                || Boolean.TRUE.equals(request.getClearDepartment()))) {
            return Mono.error(new AccessDeniedException("Only HR can change someone's manager or department"));
        }

        return userRepository.findById(id)
                .filter(user -> sameCompany(user, organizationId))
                .switchIfEmpty(Mono.error(new NotFoundException("User not found")))
                .flatMap(user -> checkManager(request.getManagerId(), user, organizationId)
                        .then(companyDepartment(request.getDepartmentId(), organizationId))
                        .map(department -> {
                            // The department's name is copied from the department,
                            // never typed: people are filed under the company's own.
                            department.ifPresent(found -> {
                                user.setDepartmentId(found.getId());
                                user.setDepartment(found.getName());
                            });
                            if (Boolean.TRUE.equals(request.getClearDepartment())) {
                                user.setDepartmentId(null);
                                user.setDepartment(null);
                            }
                            return user;
                        }))
                .flatMap(user -> {
                    if (request.getFirstName() != null) user.setFirstName(request.getFirstName());
                    if (request.getLastName() != null) user.setLastName(request.getLastName());
                    if (request.getPhone() != null) user.setPhone(request.getPhone());
                    if (request.getJobTitle() != null) user.setJobTitle(request.getJobTitle());
                    if (request.getManagerId() != null) user.setManagerId(request.getManagerId());
                    // Only the profile's own columns: a profile edit racing HR's change
                    // of role or active flag must not write the old values back.
                    return userRepository.updateProfile(user.getId(), user.getFirstName(), user.getLastName(),
                                    user.getPhone(), user.getJobTitle(), user.getManagerId(),
                                    user.getDepartmentId(), user.getDepartment(), LocalDateTime.now())
                            .then(userRepository.findById(user.getId()));
                })
                .flatMap(this::toUserResponse);
    }

    /**
     * Give a member another role. They are signed out everywhere, since a
     * session carries the role it was issued with.
     */
    @Transactional
    public Mono<UserResponse> changeRole(Long id, UserRole role, Long organizationId, Long callerId) {
        if (!INVITABLE_ROLES.contains(role)) {
            return Mono.error(new BadRequestException("A member can only be an HR manager, a manager or an employee"));
        }
        if (id.equals(callerId)) {
            return Mono.error(new BadRequestException("You can't change your own role. Ask another HR manager."));
        }
        // The company's row is held until this commits, so two HR managers
        // demoting each other at once cannot both pass the "keep one" check.
        return organizationRepository.lockForUpdate(organizationId)
                .then(managedMember(id, organizationId))
                .flatMap(user -> user.getRole() == role
                        ? Mono.just(user)
                        : keepsAnHrManager(user, organizationId, role == UserRole.HR_MANAGER)
                                .then(Mono.defer(() -> userRepository.setRole(user.getId(), organizationId, role.name(),
                                        LocalDateTime.now())))
                                .then(Mono.defer(() -> releaseJobsIfNotHiringManager(user.getId(), role)))
                                .then(Mono.defer(() -> tokenVersionCache.revokeAll(user.getId())))
                                .then(Mono.defer(() -> userRepository.findById(user.getId()))))
                .flatMap(this::toUserResponse);
    }

    /**
     * Deactivate a member, or let them back in. A deactivated member cannot
     * sign in, and every session they hold ends at once.
     */
    @Transactional
    public Mono<UserResponse> setActive(Long id, boolean active, Long organizationId, Long callerId) {
        if (!active && id.equals(callerId)) {
            return Mono.error(new BadRequestException("You can't deactivate yourself. Ask another HR manager."));
        }
        return organizationRepository.lockForUpdate(organizationId)
                .then(managedMember(id, organizationId))
                .flatMap(user -> {
                    if (Boolean.valueOf(active).equals(user.getIsActive())) {
                        return Mono.just(user);
                    }
                    Mono<Void> check = active ? Mono.empty() : keepsAnHrManager(user, organizationId, false);
                    return check
                            .then(Mono.defer(() -> userRepository.setActive(user.getId(), organizationId, active,
                                    LocalDateTime.now())))
                            // Switched off: no longer the hiring manager of any job, and
                            // signed out everywhere.
                            .then(Mono.defer(() -> active ? Mono.empty() : jobRepository.clearHiringManager(user.getId())))
                            .then(Mono.defer(() -> active ? Mono.empty() : tokenVersionCache.revokeAll(user.getId())))
                            .then(Mono.defer(() -> userRepository.findById(user.getId())));
                })
                .flatMap(this::toUserResponse);
    }

    /** A member who can no longer be a hiring manager stops being one on the company's jobs. */
    private Mono<Void> releaseJobsIfNotHiringManager(Long userId, UserRole role) {
        return role == UserRole.MANAGER || role == UserRole.HR_MANAGER
                ? Mono.empty()
                : jobRepository.clearHiringManager(userId).then();
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

        // Every attempt counts against the company's budget, refused ones too,
        // so the endpoint cannot be used to look addresses up for free.
        return requireVerifiedInviter(invitedById)
                .then(Mono.defer(() -> withinInvitationBudget(organizationId)))
                // A company AIRRAL has not approved yet keeps its invitations: they are
                // saved, and emailed on approval, so a company nobody has checked cannot
                // use AIRRAL's address to email strangers.
                .then(Mono.defer(() -> companyApproved(organizationId)))
                .flatMap(approved -> userRepository.findByEmail(email)
                        .map(Optional::of)
                        .defaultIfEmpty(Optional.empty())
                        .flatMap(existingUser -> {
                            if (existingUser.isPresent()) {
                                // One answer for any account outside this company: whether an
                                // address is a job seeker's, or another company's, is not the
                                // inviting company's business.
                                return Mono.<UserInvitation>error(new ConflictException(
                                        organizationId.equals(existingUser.get().getOrganizationId())
                                                ? email + " is already on your team"
                                                : "That address already has an AIRRAL account, so it can't be invited. "
                                                        + "Ask them for another work address."));
                            }
                            return invitationRepository.findUnacceptedByEmailAndOrganization(email, organizationId)
                                    .map(Optional::of)
                                    .defaultIfEmpty(Optional.empty())
                                    .flatMap(unaccepted -> {
                                        if (unaccepted.isPresent() && isOpen(unaccepted.get())) {
                                            return Mono.<UserInvitation>error(new ConflictException(approved
                                                    ? "Invitation already sent. Resend it from your invitations if it went astray."
                                                    : email + " is already invited. The invitation goes out when AIRRAL "
                                                            + "approves your company."));
                                        }
                                        return companyDepartment(request.getDepartmentId(), organizationId)
                                                .flatMap(department -> {
                                                    // An expired invitation is renewed rather than added
                                                    // to: the address can hold one unaccepted invitation.
                                                    UserInvitation invitation = unaccepted.orElseGet(() -> UserInvitation.builder()
                                                            .organizationId(organizationId)
                                                            .email(email)
                                                            .isAccepted(false)
                                                            .createdAt(LocalDateTime.now())
                                                            .build());
                                                    invitation.setInvitedById(invitedById);
                                                    invitation.setRole(request.getRole());
                                                    invitation.setDepartmentId(department.map(Department::getId).orElse(null));
                                                    invitation.setDepartment(department.map(Department::getName).orElse(null));
                                                    invitation.setFirstName(request.getFirstName());
                                                    invitation.setLastName(request.getLastName());
                                                    invitation.setInvitationToken(UUID.randomUUID().toString());
                                                    invitation.setExpiresAt(LocalDateTime.now().plusDays(INVITATION_DAYS));
                                                    // Not sent until it is: approval sends every unsent one.
                                                    invitation.setSentAt(null);
                                                    return invitationRepository.save(invitation);
                                                });
                                    });
                        })
                        .flatMap(invitation -> approved
                                ? sendInvitationEmail(invitation)
                                : Mono.just(toInvitationResponse(invitation, null, true))));
    }

    /** Whether AIRRAL has approved the company, so its invitations may go out. */
    private Mono<Boolean> companyApproved(Long organizationId) {
        return organizationRepository.findById(organizationId)
                .map(CompanyVerificationService::isPublishable)
                .defaultIfEmpty(false);
    }

    /**
     * Emails the invitations a company made while it waited for review, each
     * with a fresh week to accept. Called when AIRRAL approves the company.
     */
    public Mono<Long> sendHeldInvitations(Long organizationId) {
        return invitationRepository.findHeldByOrganizationId(organizationId)
                .concatMap(invitation -> {
                    invitation.setExpiresAt(LocalDateTime.now().plusDays(INVITATION_DAYS));
                    return invitationRepository.save(invitation).flatMap(this::sendInvitationEmail);
                })
                .filter(response -> Boolean.TRUE.equals(response.getEmailSent()))
                .count();
    }

    /**
     * Invitations go out from AIRRAL's own address, so the person sending them
     * must have proven theirs first: a throwaway signup cannot mail strangers.
     */
    private Mono<Void> requireVerifiedInviter(Long invitedById) {
        return userRepository.findById(invitedById)
                .filter(User::isEmailVerified)
                .switchIfEmpty(Mono.error(new BadRequestException(
                        "Verify your email address before you invite teammates. The link is in your inbox.")))
                .then();
    }

    /**
     * Get pending invitations
     */
    public Flux<InvitationResponse> getPendingInvitations(Long organizationId) {
        // Expired ones included, marked as such, so HR can renew or cancel them;
        // and held ones, which go out when AIRRAL approves the company.
        return companyApproved(organizationId).flatMapMany(approved -> invitationRepository
                .findUnacceptedByOrganizationId(organizationId)
                .map(invitation -> toInvitationResponse(invitation, null,
                        !approved && invitation.getSentAt() == null)));
    }

    /**
     * Send a pending invitation's email again, with a fresh week to accept it
     */
    public Mono<InvitationResponse> resendInvitation(Long invitationId, Long organizationId) {
        return pendingInvitation(invitationId, organizationId)
                .flatMap(invitation -> companyApproved(organizationId).flatMap(approved -> approved
                        ? Mono.just(invitation)
                        : Mono.<UserInvitation>error(new ConflictException(
                                "This invitation goes out when AIRRAL approves your company."))))
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
                .thenReturn(true)
                .onErrorResume(error -> {
                    log.warn("Invitation {} saved but its email was not sent: {}", invitation.getId(), error.getMessage());
                    return Mono.just(false);
                })
                .flatMap(sent -> !sent ? Mono.just(toInvitationResponse(invitation, false, false))
                        : invitationRepository.markSent(invitation.getId(), LocalDateTime.now())
                                .onErrorResume(error -> {
                                    log.warn("Invitation {} was emailed but not marked sent: {}",
                                            invitation.getId(), error.toString());
                                    return Mono.just(0);
                                })
                                .thenReturn(toInvitationResponse(invitation, true, false)));
    }

    private static InvitationResponse toInvitationResponse(UserInvitation invitation, Boolean emailSent, boolean held) {
        return InvitationResponse.builder()
                .id(invitation.getId())
                .email(invitation.getEmail())
                .role(invitation.getRole())
                .firstName(invitation.getFirstName())
                .lastName(invitation.getLastName())
                .department(invitation.getDepartment())
                .expiresAt(invitation.getExpiresAt())
                .expired(!isOpen(invitation))
                .held(held)
                .createdAt(invitation.getCreatedAt())
                .emailSent(emailSent)
                .build();
    }

    /**
     * A member of the caller's company whose account the company may manage.
     * AIRRAL's own admins are not a company's to demote or switch off.
     */
    private Mono<User> managedMember(Long id, Long organizationId) {
        return userRepository.findById(id)
                .filter(user -> sameCompany(user, organizationId))
                .switchIfEmpty(Mono.error(new NotFoundException("User not found")))
                .flatMap(user -> user.getRole() == UserRole.ADMIN || Boolean.TRUE.equals(user.getIsPlatformAdmin())
                        ? Mono.<User>error(new AccessDeniedException("This account is managed by AIRRAL"))
                        : Mono.just(user));
    }

    /**
     * Refuses a change that would leave the company without an active HR
     * manager: nobody would be left to run hiring or manage the team.
     */
    private Mono<Void> keepsAnHrManager(User user, Long organizationId, boolean staysHrManager) {
        boolean losesOne = user.getRole() == UserRole.HR_MANAGER
                && Boolean.TRUE.equals(user.getIsActive())
                && !staysHrManager;
        if (!losesOne) {
            return Mono.empty();
        }
        return userRepository.countActiveHrManagers(organizationId)
                .flatMap(count -> count <= 1
                        ? Mono.<Void>error(new BadRequestException(
                                "Your company needs at least one HR manager. Make someone else an HR manager first."))
                        : Mono.<Void>empty());
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

    private Mono<Optional<Department>> companyDepartment(Long departmentId, Long organizationId) {
        if (departmentId == null) {
            return Mono.just(Optional.empty());
        }
        return departmentRepository.findByIdAndOrganizationId(departmentId, organizationId)
                .map(Optional::of)
                .switchIfEmpty(Mono.error(new BadRequestException("The department must be one of your company's")));
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
