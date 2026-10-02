package com.airral.service;

import com.airral.domain.CandidateProfile;
import com.airral.domain.CandidateResumeDocument;
import com.airral.repository.CandidateProfileRepository;
import com.airral.repository.CandidateResumeDocumentRepository;
import com.airral.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Sort;
import org.springframework.util.unit.DataSize;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The one-time re-parse of stored resumes.
 *
 * <p>The document always takes the new parse. The profile's experience is what the
 * candidate sees and edits, so it is replaced only while it is untouched: empty, or
 * still exactly what this document's previous parse put there.
 */
class ResumeReparseServiceTest {

    /** What the old parser stored for r18: the employer as the title, and no years. */
    private static final String OLD_PARSE =
            "[{\"title\":\"Brightline Retail\",\"company\":\"Data Analyst\",\"startDate\":\"Jan 2022\","
                    + "\"endDate\":\"present\",\"current\":false}]";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CandidateProfileRepository profiles = mock(CandidateProfileRepository.class);
    private final CandidateResumeDocumentRepository documents = mock(CandidateResumeDocumentRepository.class);
    private final ResumeStorageService storage = mock(ResumeStorageService.class);
    private final ResumeReparseWriter writer = mock(ResumeReparseWriter.class);
    private ResumeReparseService service;

    @BeforeEach
    void setUp() throws Exception {
        CandidateProfileService candidateProfileService = new CandidateProfileService(
                profiles, documents, mock(UserRepository.class), objectMapper, storage, new ResumeParsingService(),
                "application/pdf", DataSize.ofMegabytes(5), true);
        service = new ResumeReparseService(profiles, documents, storage, candidateProfileService, writer, objectMapper);
        when(storage.load(eq(7L), any())).thenReturn(Mono.just(new ByteArrayResource(resume(
                "Experience",
                "Brightline Retail | Data Analyst",
                "Jan 2022 - Present",
                "- Wrote SQL queries in Snowflake to report weekly sales performance."))));
        when(writer.write(any(), any(), any())).thenReturn(Mono.just(true));
    }

    @Test
    @DisplayName("a profile experience the candidate edited is kept; the document still takes the new parse")
    void keepsEditedProfileExperience() {
        // The upload found the swapped job; the candidate fixed it by hand and added another.
        String edited = "[{\"title\":\"Data Analyst\",\"company\":\"Brightline Retail\",\"startDate\":\"Jan 2022\","
                + "\"endDate\":\"Present\"},{\"title\":\"Cashier\",\"company\":\"Jewel-Osco\",\"startDate\":\"2019\","
                + "\"endDate\":\"2021\"}]";

        ResumeReparseService.Report report = run(profileWith(edited), document(OLD_PARSE), false);

        assertThat(report.documentsChanged()).isEqualTo(1);
        assertThat(report.profilesUpdated()).isZero();
        assertThat(report.profilesKeptEdited()).isEqualTo(1);
        assertThat(profileWritten()).as("no profile write may be sent").isNull();
    }

    @Test
    @DisplayName("a profile the candidate emptied stays empty")
    void keepsClearedProfileExperience() {
        // The upload copied the old parse onto the empty profile; empty now means it was cleared.
        ResumeReparseService.Report report = run(profileWith("[]"), document(OLD_PARSE), false);

        assertThat(report.profilesKeptEdited()).isEqualTo(1);
        assertThat(profileWritten()).isNull();
    }

    @Test
    @DisplayName("a profile still holding exactly the old parse is replaced, whatever its key order")
    void replacesUntouchedProfileExperience() {
        // jsonb hands keys back in its own order; the same list must still count as untouched.
        String sameJobsReordered = "[{\"current\":false,\"endDate\":\"present\",\"startDate\":\"Jan 2022\","
                + "\"company\":\"Data Analyst\",\"title\":\"Brightline Retail\"}]";

        ResumeReparseService.Report report = run(profileWith(sameJobsReordered), document(OLD_PARSE), false);

        assertThat(report.profilesUpdated()).isEqualTo(1);
        ResumeReparseWriter.ProfileExperience written = profileWritten();
        assertThat(written.previousExperience()).isEqualTo(sameJobsReordered);
        assertThat(written.experience()).contains("\"title\":\"Data Analyst\"", "\"company\":\"Brightline Retail\"");
    }

    @Test
    @DisplayName("a profile left empty because the old parse found no jobs is filled")
    void fillsEmptyProfileExperience() {
        ResumeReparseService.Report report = run(profileWith("[]"), document("[]"), false);

        assertThat(report.profilesUpdated()).isEqualTo(1);
        assertThat(profileWritten().experience()).contains("Data Analyst");
    }

    @Test
    @DisplayName("a dry run writes nothing, and counts what a real run would write")
    void dryRunWritesNothing() {
        ResumeReparseService.Report report = run(profileWith("[]"), document("[]"), true);

        verify(writer, never()).write(any(), any(), any());
        assertThat(report.dryRun()).isTrue();
        assertThat(report.documentsChanged()).isEqualTo(1);
        assertThat(report.profilesUpdated()).isEqualTo(1);
    }

    @Test
    @DisplayName("a second run finds every document already up to date")
    void secondRunChangesNothing() {
        CandidateProfile profile = profileWith("[]");
        CandidateResumeDocument document = document("[]");
        run(profile, document, false);

        ResumeReparseService.Report again = run(profile, document, false);

        assertThat(again.documentsUnchanged()).isEqualTo(1);
        assertThat(again.documentsChanged()).isZero();
        verify(writer, times(1)).write(any(), any(), any());
    }

    private ResumeReparseService.Report run(CandidateProfile profile, CandidateResumeDocument document, boolean dryRun) {
        when(profiles.findAll(any(Sort.class))).thenReturn(Flux.just(profile));
        when(documents.findByIdAndUserId(document.getId(), profile.getUserId())).thenReturn(Mono.just(document));
        return service.reparse(dryRun).block(Duration.ofSeconds(10));
    }

    private ResumeReparseWriter.ProfileExperience profileWritten() {
        ArgumentCaptor<ResumeReparseWriter.ProfileExperience> profile =
                ArgumentCaptor.forClass(ResumeReparseWriter.ProfileExperience.class);
        verify(writer).write(any(), any(), profile.capture());
        return profile.getValue();
    }

    private static CandidateProfile profileWith(String experience) {
        return CandidateProfile.builder()
                .id(3L)
                .userId(7L)
                .activeResumeDocumentId(11L)
                .skills(Json.of("[]"))
                .experience(Json.of(experience))
                .education(Json.of("[]"))
                .matchPreferences(Json.of("{}"))
                .updatedAt(LocalDateTime.of(2026, 9, 30, 21, 1))
                .build();
    }

    private static CandidateResumeDocument document(String parsedExperience) {
        return CandidateResumeDocument.builder()
                .id(11L)
                .userId(7L)
                .storageProvider("LOCAL")
                .storageKey("candidate-resumes/7/resume-1.docx")
                .fileExtension(".docx")
                .parseStatus("PARSED")
                .extractedText("old text")
                .parsedSkills(Json.of("[]"))
                .parsedExperience(Json.of(parsedExperience))
                .parsedEducation(Json.of("[]"))
                .parsedProfile(Json.of("{\"experienceYears\":0.0,\"parserVersion\":\"resume-parser-v3\"}"))
                .build();
    }

    private static byte[] resume(String... jobLines) throws Exception {
        List<String> lines = new java.util.ArrayList<>(List.of("Kayla Johnson", "kayla.johnson@example.com"));
        lines.addAll(List.of(jobLines));
        lines.addAll(List.of("Summary", "Analyst who turns messy operational data into clear weekly reporting "
                + "for sales, operations and finance teams across several regions."));
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String line : lines) {
                document.createParagraph().createRun().setText(line);
            }
            document.write(out);
            return out.toByteArray();
        }
    }
}
