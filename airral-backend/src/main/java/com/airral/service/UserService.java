package com.airral.service;

import com.airral.domain.User;
import com.airral.domain.UserInvitation;
import com.airral.dto.request.InviteUserRequest;
import com.airral.dto.request.UpdateUserRequest;
import com.airral.dto.response.UserResponse;
import com.airral.repository.OrganizationRepository;
import com.airral.repository.UserInvitationRepository;
import com.airral.exception.ConflictException;
import com.airral.exception.NotFoundException;
import com.airral.repository.UserRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final UserInvitationRepository invitationRepository;
    private final OrganizationRepository organizationRepository;

    public UserService(UserRepository userRepository,
                      UserInvitationRepository invitationRepository,
                      OrganizationRepository organizationRepository) {
        this.userRepository = userRepository;
        this.invitationRepository = invitationRepository;
        this.organizationRepository = organizationRepository;
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
                .filter(user -> user.getOrganizationId().equals(organizationId))
                .switchIfEmpty(Mono.error(new NotFoundException("User not found")))
                .flatMap(this::toUserResponse);
    }

    /**
     * Get team members for a manager
     */
    public Flux<UserResponse> getTeamMembers(Long managerId) {
        return userRepository.findByManagerId(managerId)
                .flatMap(this::toUserResponse);
    }

    /**
     * Update user profile
     */
    public Mono<UserResponse> updateUser(Long id, UpdateUserRequest request, Long organizationId) {
        return userRepository.findById(id)
                .filter(user -> user.getOrganizationId().equals(organizationId))
                .switchIfEmpty(Mono.error(new NotFoundException("User not found")))
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
    public Mono<UserInvitation> inviteUser(InviteUserRequest request, Long organizationId, Long invitedById) {
        // Check if user already exists
        return userRepository.findByEmail(request.getEmail())
                .flatMap(existingUser -> {
                    if (existingUser.getOrganizationId().equals(organizationId)) {
                        return Mono.<UserInvitation>error(new ConflictException("User already exists in this organization"));
                    }
                    return Mono.<UserInvitation>error(new ConflictException("User already exists in another organization"));
                })
                .switchIfEmpty(
                    // Check if invitation already exists
                    invitationRepository.findValidInvitationByEmailAndOrganization(request.getEmail(), organizationId)
                            .flatMap(existing -> Mono.<UserInvitation>error(new ConflictException("Invitation already sent")))
                            .switchIfEmpty(
                                // Create new invitation
                                Mono.defer(() -> {
                                    String token = UUID.randomUUID().toString();
                                    UserInvitation invitation = UserInvitation.builder()
                                            .invitedById(invitedById)
                                            .organizationId(organizationId)
                                            .email(request.getEmail())
                                            .role(request.getRole())
                                            .departmentId(request.getDepartmentId())
                                            .firstName(request.getFirstName())
                                            .lastName(request.getLastName())
                                            .department(request.getDepartment())
                                            .invitationToken(token)
                                            .expiresAt(LocalDateTime.now().plusDays(7))
                                            .isAccepted(false)
                                            .createdAt(LocalDateTime.now())
                                            .build();

                                    return invitationRepository.save(invitation);
                                })
                            )
                );
    }

    /**
     * Get pending invitations
     */
    public Flux<UserInvitation> getPendingInvitations(Long organizationId) {
        return invitationRepository.findPendingByOrganizationId(organizationId);
    }

    /**
     * Convert User to UserResponse DTO
     */
    private Mono<UserResponse> toUserResponse(User user) {
        Mono<String> orgNameMono = user.getOrganizationId() != null ?
                organizationRepository.findById(user.getOrganizationId())
                        .map(org -> org.getName())
                        .defaultIfEmpty("Unknown") :
                Mono.just(null);

        Mono<String> managerNameMono = user.getManagerId() != null ?
                userRepository.findById(user.getManagerId())
                        .map(manager -> manager.getFullName())
                        .defaultIfEmpty("Unknown") :
                Mono.just(null);

        return Mono.zip(orgNameMono, managerNameMono)
                .map(tuple -> UserResponse.builder()
                        .id(user.getId())
                        .email(user.getEmail())
                        .firstName(user.getFirstName())
                        .lastName(user.getLastName())
                        .phone(user.getPhone())
                        .organizationId(user.getOrganizationId())
                        .organizationName(tuple.getT1())
                        .role(user.getRole())
                        .isPlatformAdmin(user.getIsPlatformAdmin())
                        .managerId(user.getManagerId())
                        .managerName(tuple.getT2())
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
