package com.airral.service;

import com.airral.domain.CandidateProfile;
import com.airral.domain.CandidateResumeDocument;
import com.airral.domain.User;
import com.airral.dto.request.UpdateCandidateProfileRequest;
import com.airral.dto.response.CandidateProfileResponse;
import com.airral.dto.response.ResumeReviewResponse;
import com.airral.exception.BadRequestException;
import com.airral.exception.NotFoundException;
import com.airral.repository.CandidateProfileRepository;
import com.airral.repository.CandidateResumeDocumentRepository;
import com.airral.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple2;

import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class CandidateProfileService {
    private static final Pattern SAFE_RESUME_FILE_NAME = Pattern.compile("^resume-[0-9]+\\.(pdf|docx)$");
    private static final int MAX_PARSE_ERROR_LENGTH = 1_000;

    private final CandidateProfileRepository profileRepository;
    private final CandidateResumeDocumentRepository resumeDocumentRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final ResumeStorageService resumeStorageService;
    private final ResumeParsingService resumeParsingService;
    private final List<String> allowedResumeContentTypes;
    private final long maxResumeBytes;
    private final boolean storeExtractedResumeText;

    public CandidateProfileService(
            CandidateProfileRepository profileRepository,
            CandidateResumeDocumentRepository resumeDocumentRepository,
            UserRepository userRepository,
            ObjectMapper objectMapper,
            ResumeStorageService resumeStorageService,
            ResumeParsingService resumeParsingService,
            @Value("${file.upload.allowed-types:application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document}") String allowedTypes,
            @Value("${file.upload.max-size:5MB}") DataSize maxUploadSize,
            @Value("${file.upload.store-extracted-text:true}") boolean storeExtractedResumeText) {
        this.profileRepository = profileRepository;
        this.resumeDocumentRepository = resumeDocumentRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.resumeStorageService = resumeStorageService;
        this.resumeParsingService = resumeParsingService;
        this.allowedResumeContentTypes = List.of(allowedTypes.split(",")).stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
        this.maxResumeBytes = maxUploadSize.toBytes();
        this.storeExtractedResumeText = storeExtractedResumeText;
    }

    /**
     * Get profile by user email. Auto-creates a blank profile if none exists yet.
     */
    public Mono<CandidateProfileResponse> getOrCreateProfile(String email) {
        return userRepository.findByEmail(email)
                .switchIfEmpty(Mono.error(new NotFoundException("User not found")))
                .flatMap(user ->
                        getOrCreateProfileEntity(user)
                                .map(profile -> toResponse(profile, user.getEmail(), user.getFirstName(), user.getLastName()))
                );
    }

    /**
     * Update profile fields. Recomputes profileCompletion after save.
     */
    public Mono<CandidateProfileResponse> updateProfile(String email, UpdateCandidateProfileRequest request) {
        return userRepository.findByEmail(email)
                .switchIfEmpty(Mono.error(new NotFoundException("User not found")))
                .flatMap(user ->
                        profileRepository.findByUserId(user.getId())
                                .switchIfEmpty(Mono.error(new NotFoundException("Profile not found")))
                                .flatMap(profile -> {
                                    if (request.getHeadline() != null) profile.setHeadline(request.getHeadline());
                                    if (request.getBio() != null) profile.setBio(request.getBio());
                                    if (request.getAvatarUrl() != null) profile.setAvatarUrl(request.getAvatarUrl());
                                    if (request.getLocation() != null) profile.setLocation(request.getLocation());
                                    if (request.getResumeUrl() != null) profile.setResumeUrl(request.getResumeUrl());
                                    if (request.getVideoIntroUrl() != null) profile.setVideoIntroUrl(request.getVideoIntroUrl());
                                    if (request.getOpenToWork() != null) profile.setOpenToWork(request.getOpenToWork());
                                    if (request.getPreferredEmploymentType() != null) profile.setPreferredEmploymentType(request.getPreferredEmploymentType());
                                    if (request.getPreferredWorkMode() != null) profile.setPreferredWorkMode(request.getPreferredWorkMode());
                                    if (request.getSalaryExpectationMin() != null) profile.setSalaryExpectationMin(request.getSalaryExpectationMin());
                                    if (request.getSalaryExpectationMax() != null) profile.setSalaryExpectationMax(request.getSalaryExpectationMax());
                                    if (request.getSalaryCurrency() != null) profile.setSalaryCurrency(request.getSalaryCurrency());

                                    if (request.getSkills() != null) {
                                        profile.setSkills(toJson(request.getSkills(), "[]"));
                                    }
                                    if (request.getExperience() != null) {
                                        profile.setExperience(toJson(request.getExperience(), "[]"));
                                    }
                                    if (request.getEducation() != null) {
                                        profile.setEducation(toJson(request.getEducation(), "[]"));
                                    }
                                    if (request.getMatchPreferences() != null) {
                                        profile.setMatchPreferences(toJson(request.getMatchPreferences(), "{}"));
                                    }

                                    profile.setProfileCompletion(computeCompletion(profile));
                                    profile.setUpdatedAt(LocalDateTime.now());

                                    return profileRepository.save(profile);
                                })
                                .map(profile -> toResponse(profile, user.getEmail(), user.getFirstName(), user.getLastName()))
                );
    }

    @Transactional
    public Mono<CandidateProfileResponse> uploadResume(String email, FilePart file) {
        String originalFileName = file == null ? "" : file.filename();
        String extension = resolveExtension(originalFileName);
        String contentType = contentType(file);

        return userRepository.findByEmail(email)
                .switchIfEmpty(Mono.error(new NotFoundException("User not found")))
                .flatMap(user -> validateResume(file, extension, contentType)
                        .then(resumeStorageService.store(user.getId(), file, extension, maxResumeBytes))
                        .flatMap(storedResume -> getOrCreateProfileEntity(user)
                                .flatMap(profile -> buildResumeDocument(user, profile, storedResume, originalFileName, contentType, extension)
                                        .flatMap(resumeDocumentRepository::save)
                                        .flatMap(document -> {
                                            applyResumeToProfile(profile, document);
                                            return profileRepository.save(profile);
                                        })
                                        .map(savedProfile -> toResponse(savedProfile, user.getEmail(), user.getFirstName(), user.getLastName()))
                                )
                                .onErrorResume(error -> resumeStorageService.delete(storedResume).then(Mono.error(error)))
                        )
                );
    }

    public Mono<ResumeDownload> getResume(String email, Long documentId) {
        return userRepository.findByEmail(email)
                .switchIfEmpty(Mono.error(new NotFoundException("User not found")))
                .flatMap(user -> resumeDocumentRepository.findByIdAndUserId(documentId, user.getId())
                        .switchIfEmpty(Mono.error(new NotFoundException("Resume not found")))
                        .flatMap(document -> resumeStorageService.load(user.getId(), document)
                                .map(resource -> new ResumeDownload(
                                        resource,
                                        downloadFileName(document),
                                        mediaTypeForResume(document.getFileExtension())
                                )))
                );
    }

    public Mono<ResumeDownload> getResume(String email, String fileName) {
        if (fileName == null || !SAFE_RESUME_FILE_NAME.matcher(fileName).matches()) {
            return Mono.error(new BadRequestException("Invalid resume file name"));
        }

        return userRepository.findByEmail(email)
                .switchIfEmpty(Mono.error(new NotFoundException("User not found")))
                .flatMap(user -> resumeDocumentRepository.findByStoredFileName(user.getId(), fileName)
                        .switchIfEmpty(Mono.error(new NotFoundException("Resume not found")))
                        .flatMap(document -> resumeStorageService.load(user.getId(), document)
                                .map(resource -> new ResumeDownload(
                                        resource,
                                        downloadFileName(document),
                                        mediaTypeForResume(document.getFileExtension())
                                )))
                );
    }

    public MediaType mediaTypeForResume(String fileNameOrExtension) {
        return fileNameOrExtension != null && fileNameOrExtension.toLowerCase(Locale.US).endsWith(".pdf")
                ? MediaType.APPLICATION_PDF
                : MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    /**
     * Get the user's active (most recent) resume document entity.
     * Used for resume health scoring without needing file download.
     */
    public Mono<CandidateResumeDocument> getActiveResumeDocument(String email) {
        return userRepository.findByEmail(email)
                .switchIfEmpty(Mono.error(new NotFoundException("User not found")))
                .flatMap(user -> profileRepository.findByUserId(user.getId())
                        .flatMap(profile -> profile.getActiveResumeDocumentId() == null
                                ? Mono.<CandidateResumeDocument>empty()
                                : resumeDocumentRepository.findByIdAndUserId(profile.getActiveResumeDocumentId(), user.getId()))
                        .switchIfEmpty(resumeDocumentRepository.findAllByUserIdOrderByCreatedAtDesc(user.getId()).next()));
    }

    /**
     * Return parsed resume data for user review after upload.
     * The frontend shows this so the user can confirm/edit skills, roles, and preferences
     * before the system uses them for job matching.
     */
    public Mono<ResumeReviewResponse> getResumeReview(String email) {
        return userRepository.findByEmail(email)
                .switchIfEmpty(Mono.error(new NotFoundException("User not found")))
                .flatMap(user -> profileRepository.findByUserId(user.getId())
                        .flatMap(profile -> {
                            if (profile.getActiveResumeDocumentId() == null) {
                                return Mono.<ResumeReviewResponse>error(new NotFoundException("No resume uploaded"));
                            }
                            return resumeDocumentRepository.findByIdAndUserId(profile.getActiveResumeDocumentId(), user.getId())
                                    .map(document -> toResumeReview(document, profile));
                        }));
    }

    private ResumeReviewResponse toResumeReview(CandidateResumeDocument document, CandidateProfile profile) {
        Map<String, Object> parsedProfile = parsedProfileMap(document.getParsedProfile());
        List<String> parsedSkills = fromJson(document.getParsedSkills(), String.class);
        List<CandidateProfileResponse.ExperienceEntry> experience =
                fromJson(document.getParsedExperience(), CandidateProfileResponse.ExperienceEntry.class);
        List<CandidateProfileResponse.EducationEntry> education =
                fromJson(document.getParsedEducation(), CandidateProfileResponse.EducationEntry.class);

        // Merge resume-parsed skills with any the user already has on their profile
        List<String> mergedSkills = mergeStrings(fromJson(profile.getSkills(), String.class), parsedSkills);

        // Infer target roles from headline and experience titles
        List<String> suggestedRoles = inferTargetRoles(
                mapString(parsedProfile, "headline"),
                experience);

        // Infer work mode from resume text
        String suggestedWorkMode = inferWorkMode(document.getExtractedText());

        return ResumeReviewResponse.builder()
                .resumeDocumentId(document.getId())
                .parseStatus(document.getParseStatus())
                .headline(mapString(parsedProfile, "headline"))
                .summary(mapString(parsedProfile, "summary"))
                .location(mapString(parsedProfile, "location"))
                .skills(mergedSkills)
                .experience(experience)
                .education(education)
                .suggestedTargetRoles(suggestedRoles)
                .suggestedWorkMode(suggestedWorkMode)
                .parseConfidenceScore(mapNumber(parsedProfile, "parseConfidenceScore") == null
                        ? null
                        : mapNumber(parsedProfile, "parseConfidenceScore").intValue())
                .parseWarnings(mapStringList(parsedProfile, "parseWarnings"))
                .experienceYears(mapNumber(parsedProfile, "experienceYears") == null
                        ? null
                        : mapNumber(parsedProfile, "experienceYears").doubleValue())
                .parsedAt(document.getParsedAt())
                .build();
    }

    private List<String> inferTargetRoles(String headline, List<CandidateProfileResponse.ExperienceEntry> experience) {
        Set<String> roles = new LinkedHashSet<>();

        // From headline
        String headlineRole = cleanRole(headline);
        if (headlineRole != null) {
            roles.add(headlineRole);
        }

        // From most recent experience titles
        if (experience != null) {
            for (CandidateProfileResponse.ExperienceEntry entry : experience) {
                if (entry.getTitle() != null && !entry.getTitle().isBlank()) {
                    String role = cleanRole(entry.getTitle());
                    if (role != null) {
                        roles.add(role);
                    }
                }
                if (roles.size() >= 3) {
                    break;
                }
            }
        }

        return new ArrayList<>(roles);
    }

    private String cleanRole(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String cleaned = value.strip()
                .replaceAll("(?i)\\s+at\\s+.+$", "")
                .replaceAll("\\s*[|@]\\s*.+$", "")
                .replaceAll("\\s{2,}", " ")
                .strip();
        return cleaned.length() >= 3 && cleaned.length() <= 80 ? cleaned : null;
    }

    private String inferWorkMode(String resumeText) {
        if (resumeText == null || resumeText.isBlank()) {
            return null;
        }
        String lower = resumeText.toLowerCase(Locale.US);
        if (lower.contains("remote") && (lower.contains("work") || lower.contains("position"))) {
            return "Remote";
        }
        return null;
    }

    /**
     * Compute profile completion 0-100 based on filled fields.
     */
    public int computeCompletion(CandidateProfile profile) {
        int score = 0;
        if (isNotEmpty(profile.getHeadline()))    score += 15;
        if (isNotEmpty(profile.getBio()))          score += 10;
        if (isNotEmpty(profile.getLocation()))     score += 5;
        if (isNotEmpty(profile.getAvatarUrl()))    score += 10;
        if (isNotEmpty(profile.getResumeUrl()))    score += 20;
        if (hasItems(profile.getSkills()))         score += 15;
        if (hasItems(profile.getExperience()))     score += 15;
        if (hasItems(profile.getEducation()))      score += 10;
        if (hasObject(profile.getMatchPreferences())) score += 10;
        return Math.min(score, 100);
    }

    private Mono<CandidateResumeDocument> buildResumeDocument(
            User user,
            CandidateProfile profile,
            ResumeStorageService.StoredResume storedResume,
            String originalFileName,
            String contentType,
            String extension) {
        CandidateResumeDocument storageReference = CandidateResumeDocument.builder()
                .userId(user.getId())
                .storageProvider(storedResume.storageProvider())
                .storageBucket(storedResume.storageBucket())
                .storageKey(storedResume.storageKey())
                .build();

        return resumeStorageService.load(user.getId(), storageReference)
                .flatMap(resource -> Mono.zip(sha256(resource), parseResume(resource, extension)))
                .map(result -> toResumeDocument(user, profile, storedResume, originalFileName, contentType, extension, result));
    }

    private CandidateResumeDocument toResumeDocument(
            User user,
            CandidateProfile profile,
            ResumeStorageService.StoredResume storedResume,
            String originalFileName,
            String contentType,
            String extension,
            Tuple2<String, ResumeParseOutcome> result) {
        ResumeParseOutcome parseOutcome = result.getT2();
        LocalDateTime now = LocalDateTime.now();

        return CandidateResumeDocument.builder()
                .userId(user.getId())
                .candidateProfileId(profile.getId())
                .storageProvider(storedResume.storageProvider())
                .storageBucket(storedResume.storageBucket())
                .storageKey(storedResume.storageKey())
                .originalFileName(sanitizeFileName(originalFileName))
                .contentType(contentType)
                .fileExtension(extension)
                .fileSizeBytes(storedResume.sizeBytes())
                .sha256(result.getT1())
                .parseStatus(parseOutcome.status())
                .parseError(parseOutcome.errorMessage())
                .extractedText(storeExtractedResumeText ? parseOutcome.extractedText() : null)
                .parsedSkills(toJson(parseOutcome.skills(), "[]"))
                .parsedExperience(toJson(parseOutcome.experience(), "[]"))
                .parsedEducation(toJson(parseOutcome.education(), "[]"))
                .parsedProfile(toJson(parseOutcome.parsedProfile(), "{}"))
                .parsedAt(parseOutcome.parsedAt())
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private Mono<ResumeParseOutcome> parseResume(Resource resource, String extension) {
        return resumeParsingService.parse(resource, extension)
                .map(ResumeParseOutcome::parsed)
                .onErrorResume(error -> Mono.just(ResumeParseOutcome.failed(error)));
    }

    private Mono<String> sha256(Resource resource) {
        return Mono.fromCallable(() -> {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[8192];
                try (InputStream inputStream = new DigestInputStream(resource.getInputStream(), digest)) {
                    while (inputStream.read(buffer) != -1) {
                        // DigestInputStream updates the digest while bytes are read.
                    }
                }
                return HexFormat.of().formatHex(digest.digest());
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException("SHA-256 digest is unavailable", ex);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private void applyResumeToProfile(CandidateProfile profile, CandidateResumeDocument document) {
        profile.setActiveResumeDocumentId(document.getId());
        profile.setResumeUrl(resumeStorageService.downloadUrl(document));
        profile.setResumeParseStatus(document.getParseStatus());
        profile.setResumeParsedAt(document.getParsedAt());

        if ("PARSED".equalsIgnoreCase(document.getParseStatus())) {
            List<String> parsedSkills = fromJson(document.getParsedSkills(), String.class);
            if (!parsedSkills.isEmpty()) {
                profile.setSkills(toJson(mergeStrings(fromJson(profile.getSkills(), String.class), parsedSkills), "[]"));
            }

            if (!hasItems(profile.getExperience())) {
                List<Map<String, Object>> parsedExperience = fromJsonMapList(document.getParsedExperience());
                if (!parsedExperience.isEmpty()) {
                    profile.setExperience(toJson(parsedExperience, "[]"));
                }
            }

            if (!hasItems(profile.getEducation())) {
                List<Map<String, Object>> parsedEducation = fromJsonMapList(document.getParsedEducation());
                if (!parsedEducation.isEmpty()) {
                    profile.setEducation(toJson(parsedEducation, "[]"));
                }
            }

            Map<String, Object> parsedProfile = parsedProfileMap(document.getParsedProfile());
            String parsedHeadline = mapString(parsedProfile, "headline");
            if (!isNotEmpty(profile.getHeadline()) && isNotEmpty(parsedHeadline)) {
                profile.setHeadline(parsedHeadline);
            }
            String parsedSummary = mapString(parsedProfile, "summary");
            if (!isNotEmpty(profile.getBio()) && isNotEmpty(parsedSummary)) {
                profile.setBio(parsedSummary);
            }
            String parsedLocation = mapString(parsedProfile, "location");
            if (!isNotEmpty(profile.getLocation()) && isNotEmpty(parsedLocation)) {
                profile.setLocation(parsedLocation);
            }
        }

        profile.setProfileCompletion(computeCompletion(profile));
        profile.setUpdatedAt(LocalDateTime.now());
    }

    private Mono<CandidateProfile> getOrCreateProfileEntity(User user) {
        return profileRepository.findByUserId(user.getId())
                .switchIfEmpty(profileRepository.save(blankProfile(user.getId())));
    }

    private CandidateProfile blankProfile(Long userId) {
        return CandidateProfile.builder()
                .userId(userId)
                .skills(Json.of("[]"))
                .experience(Json.of("[]"))
                .education(Json.of("[]"))
                .matchPreferences(Json.of("{}"))
                .openToWork(false)
                .profileCompletion(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private Mono<Void> validateResume(FilePart file, String extension, String contentType) {
        if (file == null) {
            return Mono.error(new BadRequestException("Resume file is required"));
        }

        boolean allowedExtension = ".pdf".equals(extension) || ".docx".equals(extension);
        boolean allowedContentType = isAllowedResumeContentType(contentType);
        long declaredLength = file.headers().getContentLength();

        if (!allowedExtension || !allowedContentType) {
            return Mono.error(new BadRequestException("Resume must be a PDF or DOCX file"));
        }
        if (declaredLength > maxResumeBytes) {
            return Mono.error(new BadRequestException("Resume file is larger than the allowed size"));
        }

        return Mono.empty();
    }

    private boolean isAllowedResumeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return true;
        }
        String normalized = contentType.trim().toLowerCase(Locale.US);
        return allowedResumeContentTypes.stream().map(value -> value.toLowerCase(Locale.US)).anyMatch(normalized::equals)
                || "application/octet-stream".equals(normalized);
    }

    private CandidateProfileResponse toResponse(CandidateProfile profile, String email, String firstName, String lastName) {
        return CandidateProfileResponse.builder()
                .id(profile.getId())
                .userId(profile.getUserId())
                .email(email)
                .firstName(firstName)
                .lastName(lastName)
                .headline(profile.getHeadline())
                .bio(profile.getBio())
                .avatarUrl(profile.getAvatarUrl())
                .location(profile.getLocation())
                .skills(fromJson(profile.getSkills(), String.class))
                .experience(fromJson(profile.getExperience(), CandidateProfileResponse.ExperienceEntry.class))
                .education(fromJson(profile.getEducation(), CandidateProfileResponse.EducationEntry.class))
                .matchPreferences(fromJsonObject(profile.getMatchPreferences(), CandidateProfileResponse.MatchPreferences.class))
                .activeResumeDocumentId(profile.getActiveResumeDocumentId())
                .resumeUrl(profile.getResumeUrl())
                .resumeParseStatus(profile.getResumeParseStatus())
                .resumeParsedAt(profile.getResumeParsedAt())
                .videoIntroUrl(profile.getVideoIntroUrl())
                .profileCompletion(profile.getProfileCompletion())
                .openToWork(profile.getOpenToWork())
                .preferredEmploymentType(profile.getPreferredEmploymentType())
                .preferredWorkMode(profile.getPreferredWorkMode())
                .salaryExpectationMin(profile.getSalaryExpectationMin())
                .salaryExpectationMax(profile.getSalaryExpectationMax())
                .salaryCurrency(profile.getSalaryCurrency())
                .createdAt(profile.getCreatedAt())
                .updatedAt(profile.getUpdatedAt())
                .build();
    }

    private Json toJson(Object value, String fallback) {
        try {
            return Json.of(objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            return Json.of(fallback);
        }
    }

    private <T> T fromJsonObject(Json json, Class<T> clazz) {
        String value = jsonToString(json);
        if (value == null || value.isBlank() || value.equals("{}") || value.equals("null")) return null;
        try {
            return objectMapper.readValue(value, clazz);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> fromJson(Json json, Class<T> clazz) {
        String value = jsonToString(json);
        if (value == null || value.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(value,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, clazz));
        } catch (JsonProcessingException e) {
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsedProfileMap(Json parsedProfile) {
        String value = jsonToString(parsedProfile);
        if (value == null || value.isBlank() || value.equals("{}") || value.equals("null")) {
            return Map.of();
        }

        try {
            return objectMapper.readValue(value, LinkedHashMap.class);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private List<Map<String, Object>> fromJsonMapList(Json json) {
        String value = jsonToString(json);
        if (value == null || value.isBlank() || value.equals("[]") || value.equals("null")) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(value,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, LinkedHashMap.class));
        } catch (JsonProcessingException e) {
            return Collections.emptyList();
        }
    }

    private String mapString(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null ? null : value.toString();
    }

    private Number mapNumber(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value instanceof Number number ? number : null;
    }

    private List<String> mapStringList(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private List<String> mergeStrings(List<String> first, List<String> second) {
        Set<String> merged = new LinkedHashSet<>();
        if (first != null) {
            first.stream().filter(this::isNotEmpty).forEach(merged::add);
        }
        if (second != null) {
            second.stream().filter(this::isNotEmpty).forEach(merged::add);
        }
        return new ArrayList<>(merged);
    }

    private boolean isNotEmpty(String value) {
        return value != null && !value.isBlank();
    }

    private boolean hasItems(Json json) {
        String value = jsonToString(json);
        if (value == null || value.isBlank()) return false;
        return !value.equals("[]") && !value.equals("null");
    }

    private boolean hasObject(Json json) {
        String value = jsonToString(json);
        if (value == null || value.isBlank()) return false;
        return !value.equals("{}") && !value.equals("null");
    }

    private String jsonToString(Json json) {
        return json == null ? null : json.asString();
    }

    private String resolveExtension(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.US);
        if (lower.endsWith(".pdf")) {
            return ".pdf";
        }
        if (lower.endsWith(".docx")) {
            return ".docx";
        }
        return "";
    }

    private String contentType(FilePart file) {
        return file == null || file.headers().getContentType() == null
                ? ""
                : file.headers().getContentType().toString();
    }

    private String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "resume";
        }
        return fileName.replaceAll("[\\r\\n\"]", "").strip();
    }

    private String downloadFileName(CandidateResumeDocument document) {
        String originalFileName = sanitizeFileName(document.getOriginalFileName());
        if (originalFileName.contains(".")) {
            return originalFileName;
        }
        return originalFileName + document.getFileExtension();
    }

    private record ResumeParseOutcome(
            String status,
            String errorMessage,
            String extractedText,
            List<String> skills,
            List<Map<String, Object>> experience,
            List<Map<String, Object>> education,
            Map<String, Object> parsedProfile,
            LocalDateTime parsedAt) {

        private static ResumeParseOutcome parsed(ResumeParsingService.ParsedResume parsedResume) {
            return new ResumeParseOutcome(
                    "PARSED",
                    null,
                    parsedResume.extractedText(),
                    nullToEmpty(parsedResume.skills()),
                    nullToEmpty(parsedResume.experience()),
                    nullToEmpty(parsedResume.education()),
                    parsedResume.parsedProfile() == null ? new LinkedHashMap<>() : parsedResume.parsedProfile(),
                    LocalDateTime.now()
            );
        }

        private static ResumeParseOutcome failed(Throwable error) {
            Map<String, Object> parsedProfile = new LinkedHashMap<>();
            parsedProfile.put("parserVersion", "resume-parser-v3");
            parsedProfile.put("parsedAt", LocalDateTime.now().toString());
            return new ResumeParseOutcome(
                    "PARSE_FAILED",
                    truncate(error.getMessage()),
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    parsedProfile,
                    null
            );
        }

        private static <T> List<T> nullToEmpty(List<T> value) {
            return value == null ? List.of() : value;
        }

        private static String truncate(String value) {
            if (value == null || value.length() <= MAX_PARSE_ERROR_LENGTH) {
                return value;
            }
            return value.substring(0, MAX_PARSE_ERROR_LENGTH);
        }
    }

    public record ResumeDownload(Resource resource, String fileName, MediaType mediaType) {
    }
}
