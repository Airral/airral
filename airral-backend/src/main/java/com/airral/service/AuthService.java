package com.airral.service;

import com.airral.domain.CandidateProfile;
import com.airral.domain.Organization;
import com.airral.domain.User;
import com.airral.domain.enums.OrganizationTier;
import com.airral.domain.enums.SubscriptionStatus;
import com.airral.domain.enums.UserRole;
import com.airral.dto.request.LoginRequest;
import com.airral.dto.request.GoogleAuthRequest;
import com.airral.dto.request.RegisterRequest;
import com.airral.dto.response.AuthResponse;
import com.airral.exception.BadRequestException;
import com.airral.exception.ConflictException;
import com.airral.exception.NotFoundException;
import com.airral.exception.UnauthorizedException;
import com.airral.repository.CandidateProfileRepository;
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
import java.util.UUID;

@Service
public class AuthService {

    private static final String DEFAULT_DEPARTMENT = "Human Resources";
    private static final String DEFAULT_JOB_TITLE = "HR Manager";

    /**
     * Answer to a Google sign-in that resolves to an account Google has not
     * earned the right to open.
     *
     * <p>It discloses that an account exists at the address and nothing beyond
     * that, which is exactly what POST /api/auth/register already answers with
     * its 409 "Email already registered" -- so it widens no enumeration that is
     * not already there. It deliberately does not say whether the account was
     * verified, what role it holds, or whether another Google identity sits on
     * it: those are the facts that would tell someone which of the addresses
     * they had pre-registered a real person has since come looking for.
     */
    private static final String GOOGLE_LINK_REFUSED =
            "An account already exists for this email address. Sign in with your password to continue.";

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final CandidateProfileRepository candidateProfileRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final ObjectMapper objectMapper;
    private final GoogleIdentityService googleIdentityService;

    public AuthService(UserRepository userRepository,
                      OrganizationRepository organizationRepository,
                      CandidateProfileRepository candidateProfileRepository,
                      PasswordEncoder passwordEncoder,
                      JwtTokenProvider jwtTokenProvider,
                      ObjectMapper objectMapper,
                      GoogleIdentityService googleIdentityService) {
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.candidateProfileRepository = candidateProfileRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.objectMapper = objectMapper;
        this.googleIdentityService = googleIdentityService;
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
                            .flatMap(savedUser -> buildAuthResponse(savedUser, "Login successful"));
                });
    }

    /**
     * Sign in or create an applicant account from a verified Google Identity Services token.
     *
     * <p>The Google sub is looked up before the address, and that order is the
     * defence. Matching on the address alone signs the caller into whichever row
     * already holds it, no matter who put it there: /api/auth/register is
     * public, registerApplicant stores emailVerified false, and no verification
     * mail is ever sent (SMTP does not work from Cloud Run here), so an
     * unverified row carries no evidence at all about who owns the address.
     * Register victim@example.com with a password of your choosing, wait for the
     * victim to press "Continue with Google", and you keep password access to
     * the resume and applications they then file. The same path reaches ADMIN,
     * since BootstrapAdminInitializer promotes whichever row holds the bootstrap
     * address at the next boot.
     *
     * <p>Never live: POST /api/auth/google had no handler until the mapping
     * added alongside this, so the address-only match never ran in production
     * and no row was hijacked by it. Said explicitly because the alternative
     * reading sends someone hunting for compromised accounts that cannot exist.
     */
    public Mono<AuthResponse> loginWithGoogle(GoogleAuthRequest request) {
        return googleIdentityService.verifyCredential(request.getCredential())
                .flatMap(profile -> userRepository.findByGoogleSubject(profile.subject())
                        .flatMap(linkedUser -> loginExistingGoogleUser(linkedUser, profile))
                        .switchIfEmpty(Mono.defer(() -> linkOrCreateGoogleAccount(profile))));
    }

    /**
     * No row carries this Google sub yet, so the address is all there is to go
     * on -- and it is only enough when the row it finds has itself already
     * proved that address.
     */
    private Mono<AuthResponse> linkOrCreateGoogleAccount(GoogleIdentityService.GoogleProfile profile) {
        return userRepository.findByEmail(profile.email())
                .flatMap(existingUser -> {
                    if (!isSafeToLink(existingUser)) {
                        return Mono.<AuthResponse>error(new UnauthorizedException(GOOGLE_LINK_REFUSED));
                    }

                    existingUser.setGoogleSubject(profile.subject());
                    return loginExistingGoogleUser(existingUser, profile);
                })
                .switchIfEmpty(Mono.defer(() -> registerGoogleApplicant(profile)));
    }

    /**
     * Whether a Google identity that has never been linked may adopt the row
     * sitting at its address.
     *
     * <p>Two refusals. A row already holding a sub is somebody's linked Google
     * account -- and since the lookup by sub came back empty it is a different
     * one, so re-pointing it would hand this caller their session. A row that
     * was never email-verified has proved nothing: registration is public and
     * unverified here, so the row may well have been created by someone who
     * simply typed the address in first.
     *
     * <p>Which today refuses every existing account, and that is worth knowing
     * before reading a support ticket about it. Nothing in this service sets
     * emailVerified true except the two Google paths below, and the third
     * candidate does not count: registerWithInvitation flips it, but it resolves
     * the token against users.invitation_token, and nothing writes that column
     * -- UserService.inviteUser saves to the separate user_invitations table --
     * so that branch never matches. Every row in users is therefore unverified,
     * and until an account is created here through Google, the only outcomes on
     * the address fallback are "refused" and "new account".
     */
    private boolean isSafeToLink(User user) {
        return !StringUtils.hasText(user.getGoogleSubject()) && user.isEmailVerified();
    }

    private Mono<AuthResponse> loginExistingGoogleUser(User user, GoogleIdentityService.GoogleProfile profile) {
        if (!user.isActive()) {
            return Mono.error(new UnauthorizedException("Account is deactivated"));
        }

        if (!StringUtils.hasText(user.getFirstName())) {
            user.setFirstName(resolveFirstName(profile));
        }
        if (!StringUtils.hasText(user.getLastName())) {
            user.setLastName(resolveLastName(profile));
        }
        user.setEmailVerified(true);
        user.setLastLoginAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());

        Mono<User> savedUser = userRepository.save(user);
        if (user.getRole() == UserRole.APPLICANT) {
            savedUser = savedUser.flatMap(this::ensureApplicantProfile);
        }

        return savedUser.flatMap(updatedUser -> buildAuthResponse(updatedUser, "Google sign-in successful"));
    }

    private Mono<AuthResponse> registerGoogleApplicant(GoogleIdentityService.GoogleProfile profile) {
        User user = User.builder()
                .email(profile.email())
                .googleSubject(profile.subject())
                .passwordHash(passwordEncoder.encode("GOOGLE:" + profile.subject() + ":" + UUID.randomUUID()))
                .firstName(resolveFirstName(profile))
                .lastName(resolveLastName(profile))
                .phone(null)
                .organizationId(null)
                .role(UserRole.APPLICANT)
                .isPlatformAdmin(false)
                .isActive(true)
                .emailVerified(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .lastLoginAt(LocalDateTime.now())
                .build();

        return userRepository.save(user)
                .flatMap(this::ensureApplicantProfile)
                .flatMap(savedUser -> buildAuthResponse(savedUser, "Google account created", true));
    }

    private Mono<User> ensureApplicantProfile(User user) {
        return candidateProfileRepository.existsByUserId(user.getId())
                .flatMap(exists -> exists ? Mono.just(user) : createApplicantProfile(user).thenReturn(user));
    }

    private String resolveFirstName(GoogleIdentityService.GoogleProfile profile) {
        if (StringUtils.hasText(profile.firstName())) {
            return profile.firstName();
        }
        if (StringUtils.hasText(profile.fullName())) {
            String[] parts = profile.fullName().trim().split("\\s+", 2);
            return parts[0];
        }
        return profile.email().split("@", 2)[0];
    }

    private String resolveLastName(GoogleIdentityService.GoogleProfile profile) {
        if (StringUtils.hasText(profile.lastName())) {
            return profile.lastName();
        }
        if (StringUtils.hasText(profile.fullName())) {
            String[] parts = profile.fullName().trim().split("\\s+", 2);
            return parts.length > 1 ? parts[1] : "";
        }
        return "";
    }

    /**
     * Register new user (self-registration creates new organization)
     */
    @Transactional
    public Mono<AuthResponse> register(RegisterRequest request) {
        boolean invitedFlow = StringUtils.hasText(request.getInvitationToken());

        // Names are required for employer/org signup, but applicant signup can collect profile details later.
        boolean employerSignup = StringUtils.hasText(request.getCompanyName());
        if (employerSignup && (!StringUtils.hasText(request.getFirstName()) || !StringUtils.hasText(request.getLastName()))) {
            return Mono.error(new BadRequestException("First name and last name are required"));
        }

        // Check if email already exists
        return userRepository.existsByEmail(request.getEmail())
                .flatMap(exists -> {
                    if (exists) {
                        return Mono.<AuthResponse>error(new ConflictException("Email already registered"));
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
    private Mono<AuthResponse> registerApplicant(RegisterRequest request) {
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
                .flatMap(savedUser -> createApplicantProfile(savedUser).thenReturn(savedUser))
                .flatMap(savedUser -> buildAuthResponse(savedUser, "Registration successful", true));
    }

    private Mono<CandidateProfile> createApplicantProfile(User user) {
        CandidateProfile profile = CandidateProfile.builder()
                .userId(user.getId())
                .skills(Json.of("[]"))
                .experience(Json.of("[]"))
                .education(Json.of("[]"))
                .matchPreferences(Json.of("{}"))
                .openToWork(false)
                .profileCompletion(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        return candidateProfileRepository.save(profile);
    }

    /**
     * Self-registration: Create new organization + HR Manager
     */
    private Mono<AuthResponse> registerWithNewOrganization(RegisterRequest request) {
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
                                        .flatMap(savedUser -> buildAuthResponse(savedUser, "Organization and account created successfully", true));
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
    private Mono<AuthResponse> registerWithInvitation(RegisterRequest request) {
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
                            .flatMap(savedUser -> buildAuthResponse(savedUser, "Account activated successfully"));
                });
    }

    /**
     * Build JWT auth response
     */
    private Mono<AuthResponse> buildAuthResponse(User user, String message) {
        return buildAuthResponse(user, message, false);
    }

    private Mono<AuthResponse> buildAuthResponse(User user, String message, boolean accountCreated) {
        if (user.getOrganizationId() == null) {
            String token = jwtTokenProvider.generateToken(
                user.getId(),
                user.getEmail(),
                user.getRole().name(),
                null,
                null,
                user.isPlatformAdmin(),
                user.getDepartment(),
                user.getManagerId(),
                user.getTokenVersion() == null ? 0 : user.getTokenVersion()
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
                .message(message)
                .accountCreated(accountCreated)
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
                            user.getManagerId(),
                        user.getTokenVersion() == null ? 0 : user.getTokenVersion()
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
                            .message(message)
                            .accountCreated(accountCreated)
                            .build();
                });
    }
}
