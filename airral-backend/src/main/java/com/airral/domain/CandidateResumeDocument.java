package com.airral.domain;

import io.r2dbc.postgresql.codec.Json;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("candidate_resume_documents")
public class CandidateResumeDocument {

    @Id
    private Long id;

    private Long userId;
    private Long candidateProfileId;

    private String storageProvider;
    private String storageBucket;
    private String storageKey;

    private String originalFileName;
    private String contentType;
    private String fileExtension;
    private Long fileSizeBytes;
    private String sha256;

    private String parseStatus;
    private String parseError;
    private String extractedText;
    private Json parsedSkills;
    private Json parsedExperience;
    private Json parsedEducation;
    private Json parsedProfile;
    private LocalDateTime parsedAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
