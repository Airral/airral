package com.airral.service;

import com.airral.domain.CandidateProfile;
import com.airral.domain.User;
import com.airral.dto.request.UpdateCandidateProfileRequest;
import com.airral.dto.response.CandidateProfileResponse;
import com.airral.exception.BadRequestException;
import com.airral.exception.NotFoundException;
import com.airral.repository.CandidateProfileRepository;
import com.airral.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class CandidateProfileService {
    private static final Pattern SAFE_RESUME_FILE_NAME = Pattern.compile("^resume-[0-9]+\\.(pdf|docx)$");

    private final CandidateProfileRepository profileRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final Path resumeStorageRoot;
    private final List<String> allowedResumeContentTypes;
    private final long maxResumeBytes;

    public CandidateProfileService(
            CandidateProfileRepository profileRepository,
            UserRepository userRepository,
            ObjectMapper objectMapper,
            @Value("${file.upload.storage-path:./uploads}") String storagePath,
            @Value("${file.upload.allowed-types:application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document}") String allowedTypes,
            @Value("${file.upload.max-size:5MB}") DataSize maxUploadSize) {
        this.profileRepository = profileRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.resumeStorageRoot = Path.of(storagePath).toAbsolutePath().normalize().resolve("candidate-resumes");
        this.allowedResumeContentTypes = List.of(allowedTypes.split(",")).stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
        this.maxResumeBytes = maxUploadSize.toBytes();
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

    public Mono<CandidateProfileResponse> uploadResume(String email, FilePart file) {
        return userRepository.findByEmail(email)
                .switchIfEmpty(Mono.error(new NotFoundException("User not found")))
                .flatMap(user -> validateResume(file)
                        .then(resolveResumePath(user.getId(), file))
                        .flatMap(targetPath -> file.transferTo(targetPath)
                                .then(validateStoredResumeSize(targetPath))
                                .then(getOrCreateProfileEntity(user))
                                .flatMap(profile -> {
                                    profile.setResumeUrl("/api/candidate/profile/resume/" + targetPath.getFileName());
                                    profile.setProfileCompletion(computeCompletion(profile));
                                    profile.setUpdatedAt(LocalDateTime.now());
                                    return profileRepository.save(profile);
                                })
                                .map(profile -> toResponse(profile, user.getEmail(), user.getFirstName(), user.getLastName()))
                        )
                );
    }

    public Mono<Resource> getResume(String email, String fileName) {
        if (fileName == null || !SAFE_RESUME_FILE_NAME.matcher(fileName).matches()) {
            return Mono.error(new BadRequestException("Invalid resume file name"));
        }

        return userRepository.findByEmail(email)
                .switchIfEmpty(Mono.error(new NotFoundException("User not found")))
                .flatMap(user -> Mono.fromCallable(() -> {
                    Path filePath = resumeStorageRoot.resolve(String.valueOf(user.getId())).resolve(fileName).normalize();
                    if (!filePath.startsWith(resumeStorageRoot) || !Files.exists(filePath)) {
                        throw new NotFoundException("Resume not found");
                    }
                    return (Resource) new FileSystemResource(filePath);
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    public MediaType mediaTypeForResume(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.US).endsWith(".pdf")
                ? MediaType.APPLICATION_PDF
                : MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
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

    // --- Helpers ---

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

    private Mono<Void> validateResume(FilePart file) {
        if (file == null) {
            return Mono.error(new BadRequestException("Resume file is required"));
        }

        String fileName = file.filename() == null ? "" : file.filename().toLowerCase(Locale.US);
        boolean allowedExtension = fileName.endsWith(".pdf") || fileName.endsWith(".docx");
        String contentType = file.headers().getContentType() == null ? "" : file.headers().getContentType().toString();
        boolean allowedContentType = allowedResumeContentTypes.contains(contentType);
        long declaredLength = file.headers().getContentLength();

        if (!allowedExtension || !allowedContentType) {
            return Mono.error(new BadRequestException("Resume must be a PDF or DOCX file"));
        }
        if (declaredLength > maxResumeBytes) {
            return Mono.error(new BadRequestException("Resume file is larger than the allowed size"));
        }

        return Mono.empty();
    }

    private Mono<Path> resolveResumePath(Long userId, FilePart file) {
        return Mono.fromCallable(() -> {
            String extension = file.filename().toLowerCase(Locale.US).endsWith(".pdf") ? ".pdf" : ".docx";
            Path userDirectory = resumeStorageRoot.resolve(String.valueOf(userId)).normalize();
            Files.createDirectories(userDirectory);
            return userDirectory.resolve("resume-" + System.currentTimeMillis() + extension).normalize();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Path> validateStoredResumeSize(Path targetPath) {
        return Mono.fromCallable(() -> {
            long actualSize = Files.size(targetPath);
            if (actualSize > maxResumeBytes) {
                Files.deleteIfExists(targetPath);
                throw new BadRequestException("Resume file is larger than the allowed size");
            }
            return targetPath;
        }).subscribeOn(Schedulers.boundedElastic());
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
                .resumeUrl(profile.getResumeUrl())
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
}
