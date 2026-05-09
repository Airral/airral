package com.airral.service;

import com.airral.domain.Organization;
import com.airral.domain.User;
import com.airral.domain.enums.OrganizationTier;
import com.airral.domain.enums.SubscriptionStatus;
import com.airral.domain.enums.UserRole;
import com.airral.dto.request.LoginRequest;
import com.airral.dto.request.RegisterRequest;
import com.airral.dto.response.AuthResponse;
import com.airral.exception.BadRequestException;
import com.airral.exception.ConflictException;
import com.airral.exception.NotFoundException;
import com.airral.exception.UnauthorizedException;
import com.airral.repository.OrganizationRepository;
import com.airral.repository.UserRepository;
import com.airral.security.JwtTokenProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public class AuthService {

    private static final String DEFAULT_DEPARTMENT = "Human Resources";
    private static final String DEFAULT_JOB_TITLE = "HR Manager";

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final ObjectMapper objectMapper;

    public AuthService(UserRepository userRepository,
                      OrganizationRepository organizationRepository,
                      PasswordEncoder passwordEncoder,
                      JwtTokenProvider jwtTokenProvider,
                      ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.objectMapper = objectMapper;
    }

    /**
     * Login user
     */
    public Mono<AuthResponse> login(LoginRequest request) {
        return userRepository.findByEmail(request.getEmail())
                .switchIfEmpty(Mono.error(new UnauthorizedException("Invalid email or password")))
                .flatMap(user -> {
                    // Verify password
                    if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
                        return Mono.error(new UnauthorizedException("Invalid email or password"));
                    }

                    // Check if user is active
                    if (!user.isActive()) {
                        return Mono.error(new UnauthorizedException("Account is deactivated"));
                    }

                    // Update last login
                    user.setLastLoginAt(LocalDateTime.now());
                    return userRepository.save(user)
                            .flatMap(savedUser -> buildAuthResponse(savedUser));
                });
    }

    /**
     * Register new user (self-registration creates new organization)
     */
    @Transactional
    public Mono<String> register(RegisterRequest request) {
        boolean invitedFlow = StringUtils.hasText(request.getInvitationToken());

        // Names are required for self-signup, optional for invite flow.
        if (!invitedFlow && (!StringUtils.hasText(request.getFirstName()) || !StringUtils.hasText(request.getLastName()))) {
            return Mono.error(new BadRequestException("First name and last name are required"));
        }

        // Check if email already exists
        return userRepository.existsByEmail(request.getEmail())
                .flatMap(exists -> {
                    if (exists) {
                        return Mono.<String>error(new ConflictException("Email already registered"));
                    }

                    // Self-registration (create new organization)
                    if (StringUtils.hasText(request.getCompanyName())) {
                        return registerWithNewOrganization(request);
                    }

                    // Invited user (join existing organization)
                    if (invitedFlow) {
                        return registerWithInvitation(request);
                    }

                    // Applicant self-registration (no organization)
                    return registerApplicant(request);
                });
    }

    /**
     * Applicant self-registration
     */
    private Mono<String> registerApplicant(RegisterRequest request) {
        User user = User.builder()
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .phone(request.getPhone())
                .organizationId(null)
                .role(UserRole.APPLICANT)
                .isPlatformAdmin(false)
                .isActive(true)
                .emailVerified(false)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        return userRepository.save(user)
                .thenReturn("Registration successful. Please check your email to verify your account.");
    }

    /**
     * Self-registration: Create new organization + HR Manager
     */
    private Mono<String> registerWithNewOrganization(RegisterRequest request) {
        return parseTier(request.getOrganizationTier())
                .flatMap(tier -> {
                    Organization organization = Organization.builder()
                            .name(request.getCompanyName())
                            .domain(request.getCompanyDomain())
                            .tier(tier)
                            .subscriptionStatus(SubscriptionStatus.TRIAL)
                            .maxUsers(request.getMaxUsers())
                            .maxJobs(request.getMaxJobs())
                            .maxApplicationsPerMonth(request.getMaxApplicationsPerMonth())
                            .primaryContactEmail(request.getPrimaryContactEmail())
                            .primaryContactPhone(request.getPrimaryContactPhone())
                            .billingEmail(request.getBillingEmail())
                            .timezone(request.getTimezone())
                            .country(request.getCountry())
                            .companySizeRange(request.getCompanySizeRange())
                            .industry(request.getIndustry())
                            .logoUrl(request.getLogoUrl())
                            .brandPrimaryColor(request.getBrandPrimaryColor())
                            .brandSecondaryColor(request.getBrandSecondaryColor())
                            .subscriptionPlanPreference(request.getSubscriptionPlanPreference())
                            .hiringRegions(request.getHiringRegions())
                            .offices(request.getOffices())
                            .departmentStructure(request.getDepartmentStructure())
                            .compliancePreferences(toJson(request.getCompliancePreferences(), "compliancePreferences"))
                            .customSettings(toJson(request.getCustomSettings(), "customSettings"))
                            .settings(null)
                            .isActive(true)
                            .createdAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                            .build();

                    return organizationRepository.save(organization)
                            .flatMap(savedOrg -> {
                                User user = User.builder()
                                        .email(request.getEmail())
                                        .passwordHash(passwordEncoder.encode(request.getPassword()))
                                        .firstName(request.getFirstName())
                                        .lastName(request.getLastName())
                                        .phone(request.getPhone())
                                        .organizationId(savedOrg.getId())
                                        .role(UserRole.HR_MANAGER)
                                        .isPlatformAdmin(false)
                                        .department(DEFAULT_DEPARTMENT)
                                        .jobTitle(DEFAULT_JOB_TITLE)
                                        .isActive(true)
                                        .emailVerified(false)
                                        .createdAt(LocalDateTime.now())
                                        .updatedAt(LocalDateTime.now())
                                        .build();

                                return userRepository.save(user)
                                        .thenReturn("Organization and account created successfully. Please check your email to verify your account.");
                            });
                });
    }

    private Mono<OrganizationTier> parseTier(String requestedTier) {
        if (!StringUtils.hasText(requestedTier)) {
            return Mono.just(OrganizationTier.QUICK_HIRE);
        }

        try {
            return Mono.just(OrganizationTier.valueOf(requestedTier.trim().toUpperCase()));
        } catch (IllegalArgumentException ex) {
            return Mono.error(new BadRequestException("Invalid organizationTier. Use QUICK_HIRE, PROFESSIONAL, or ENTERPRISE."));
        }
    }

    private Json toJson(Map<String, Object> value, String fieldName) {
        if (value == null) {
            return null;
        }

        try {
            return Json.of(objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException ex) {
            throw new BadRequestException("Invalid JSON payload for " + fieldName);
        }
    }

    /**
     * Invited user registration
     */
    private Mono<String> registerWithInvitation(RegisterRequest request) {
        return userRepository.findByValidInvitationToken(request.getInvitationToken())
                .switchIfEmpty(Mono.error(new BadRequestException("Invalid or expired invitation")))
                .flatMap(user -> {
                    // Set password and activate user
                    user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
                    if (StringUtils.hasText(request.getFirstName())) {
                        user.setFirstName(request.getFirstName());
                    }
                    if (StringUtils.hasText(request.getLastName())) {
                        user.setLastName(request.getLastName());
                    }
                    if (StringUtils.hasText(request.getPhone())) {
                        user.setPhone(request.getPhone());
                    }
                    user.setInvitationToken(null);
                    user.setInvitationExpiresAt(null);
                    user.setEmailVerified(true);
                    user.setIsActive(true);
                    user.setUpdatedAt(LocalDateTime.now());

                    return userRepository.save(user)
                            .thenReturn("Account activated successfully. You can now log in.");
                });
    }

    /**
     * Build JWT auth response
     */
    private Mono<AuthResponse> buildAuthResponse(User user) {
        if (user.getOrganizationId() == null) {
            String token = jwtTokenProvider.generateToken(
                user.getId(),
                user.getEmail(),
                user.getRole().name(),
                null,
                null,
                user.isPlatformAdmin(),
                user.getDepartment(),
                user.getManagerId()
            );

            return Mono.just(AuthResponse.builder()
                .token(token)
                .type("Bearer")
                .userId(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .role(user.getRole().name())
                .organizationId(null)
                .organizationName(null)
                .organizationTier(null)
                .isPlatformAdmin(user.isPlatformAdmin())
                .emailVerified(user.isEmailVerified())
                .message("Login successful")
                .build());
        }

        return organizationRepository.findById(user.getOrganizationId())
            .switchIfEmpty(Mono.error(new NotFoundException("Organization not found for user " + user.getId())))
                .map(org -> {
                    String token = jwtTokenProvider.generateToken(
                            user.getId(),
                            user.getEmail(),
                            user.getRole().name(),
                            user.getOrganizationId(),
                            org.getTier().name(),
                            user.isPlatformAdmin(),
                            user.getDepartment(),
                            user.getManagerId()
                    );

                    return AuthResponse.builder()
                            .token(token)
                            .type("Bearer")
                            .userId(user.getId())
                            .email(user.getEmail())
                            .firstName(user.getFirstName())
                            .lastName(user.getLastName())
                            .role(user.getRole().name())
                            .organizationId(org.getId())
                            .organizationName(org.getName())
                            .organizationTier(org.getTier().name())
                            .isPlatformAdmin(user.isPlatformAdmin())
                            .emailVerified(user.isEmailVerified())
                            .message("Login successful")
                            .build();
                });
    }
}
