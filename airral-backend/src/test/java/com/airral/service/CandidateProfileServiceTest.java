package com.airral.service;

import com.airral.domain.CandidateProfile;
import com.airral.domain.CandidateResumeDocument;
import com.airral.repository.CandidateProfileRepository;
import com.airral.repository.CandidateResumeDocumentRepository;
import com.airral.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CandidateProfileServiceTest {

    @Test
    void appliesParsedResumeDataToEmptyProfileFields() {
        ResumeStorageService storageService = mock(ResumeStorageService.class);
        when(storageService.downloadUrl(any(CandidateResumeDocument.class))).thenReturn("/api/candidate/profile/resume/7/download");

        CandidateProfileService service = new CandidateProfileService(
                mock(CandidateProfileRepository.class),
                mock(CandidateResumeDocumentRepository.class),
                mock(UserRepository.class),
                new ObjectMapper(),
                storageService,
                mock(ResumeParsingService.class),
                "application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                DataSize.ofMegabytes(5),
                true
        );

        CandidateProfile profile = CandidateProfile.builder()
                .skills(Json.of("[\"Existing Skill\"]"))
                .experience(Json.of("[]"))
                .education(Json.of("[]"))
                .matchPreferences(Json.of("{}"))
                .profileCompletion(0)
                .build();
        LocalDateTime parsedAt = LocalDateTime.now();
        CandidateResumeDocument document = CandidateResumeDocument.builder()
                .id(7L)
                .parseStatus("PARSED")
                .parsedSkills(Json.of("[\"Java\",\"React\"]"))
                .parsedExperience(Json.of("[{\"company\":\"The Home Depot\",\"title\":\"Software Engineer II\",\"startDate\":\"June 2024\",\"endDate\":\"Present\",\"description\":\"Built services\",\"current\":true}]"))
                .parsedEducation(Json.of("[{\"school\":\"University of Massachusetts, Boston\",\"degree\":\"Bachelor of Science in Computer Science\",\"field\":\"Computer Science\"}]"))
                .parsedProfile(Json.of("{\"headline\":\"Software Engineer II at The Home Depot\",\"summary\":\"Software Engineer building cloud-native systems.\",\"location\":\"Boston, MA\"}"))
                .parsedAt(parsedAt)
                .build();

        ReflectionTestUtils.invokeMethod(service, "applyResumeToProfile", profile, document);

        assertThat(profile.getActiveResumeDocumentId()).isEqualTo(7L);
        assertThat(profile.getResumeUrl()).isEqualTo("/api/candidate/profile/resume/7/download");
        assertThat(profile.getResumeParseStatus()).isEqualTo("PARSED");
        assertThat(profile.getResumeParsedAt()).isEqualTo(parsedAt);
        assertThat(profile.getHeadline()).isEqualTo("Software Engineer II at The Home Depot");
        assertThat(profile.getBio()).isEqualTo("Software Engineer building cloud-native systems.");
        assertThat(profile.getLocation()).isEqualTo("Boston, MA");
        assertThat(profile.getSkills().asString()).contains("Existing Skill", "Java", "React");
        assertThat(profile.getExperience().asString()).contains("The Home Depot", "Software Engineer II");
        assertThat(profile.getEducation().asString()).contains("University of Massachusetts, Boston", "Computer Science");
        assertThat(profile.getProfileCompletion()).isGreaterThanOrEqualTo(85);
    }
}
