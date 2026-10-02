package com.airral.service;

import com.airral.domain.CandidateProfile;
import com.airral.domain.CandidateResumeDocument;
import com.airral.repository.CandidateProfileRepository;
import com.airral.repository.CandidateResumeDocumentRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.r2dbc.postgresql.codec.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Objects;

/**
 * Re-parses the active resume of every profile with the current parser.
 *
 * <p>A document keeps the parse it got at upload. Resumes uploaded before v1.28.5
 * still carry the old one: 0 jobs and 0.0 years for most of the 22 sample resumes,
 * and the job fit, the ranking's years filter and the profile's experience all read
 * those stored fields, not the file. This brings them up to date once. See
 * {@link ResumeReparseRunner} for how it is run.
 *
 * <p>The document is rewritten as an upload of the same file today would write it,
 * through {@link CandidateProfileService#applyParseOutcome}. The profile is more
 * careful: its experience is what the candidate sees and edits, so it is replaced
 * only while it still holds exactly what this document's previous parse put there --
 * nothing, for the many that found no jobs. Anything else is the candidate's own
 * work, or came from another resume, and is kept.
 */
@Service
@Profile(ResumeReparseRunner.PROFILE)
public class ResumeReparseService {

    private static final Logger log = LoggerFactory.getLogger(ResumeReparseService.class);

    private final CandidateProfileRepository profileRepository;
    private final CandidateResumeDocumentRepository resumeDocumentRepository;
    private final ResumeStorageService resumeStorageService;
    private final CandidateProfileService candidateProfileService;
    private final ResumeReparseWriter writer;
    private final ObjectMapper objectMapper;

    public ResumeReparseService(
            CandidateProfileRepository profileRepository,
            CandidateResumeDocumentRepository resumeDocumentRepository,
            ResumeStorageService resumeStorageService,
            CandidateProfileService candidateProfileService,
            ResumeReparseWriter writer,
            ObjectMapper objectMapper) {
        this.profileRepository = profileRepository;
        this.resumeDocumentRepository = resumeDocumentRepository;
        this.resumeStorageService = resumeStorageService;
        this.candidateProfileService = candidateProfileService;
        this.writer = writer;
        this.objectMapper = objectMapper;
    }

    /**
     * Re-parses every active resume, one at a time. With {@code dryRun} nothing is
     * written; every line and count reads as what a real run would do.
     */
    public Mono<Report> reparse(boolean dryRun) {
        return profileRepository.findAll(Sort.by("id"))
                .filter(profile -> profile.getActiveResumeDocumentId() != null)
                .concatMap(profile -> reparseActiveResume(profile, dryRun))
                .reduce(Report.empty(dryRun), Report::plus);
    }

    private Mono<Outcome> reparseActiveResume(CandidateProfile profile, boolean dryRun) {
        return resumeDocumentRepository.findByIdAndUserId(profile.getActiveResumeDocumentId(), profile.getUserId())
                .flatMap(document -> reparseDocument(profile, document, dryRun))
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    log.warn("Re-parse profile {}: active resume {} not found for user {}; skipped",
                            profile.getId(), profile.getActiveResumeDocumentId(), profile.getUserId());
                    return Outcome.MISSING;
                }));
    }

    private Mono<Outcome> reparseDocument(CandidateProfile profile, CandidateResumeDocument document, boolean dryRun) {
        Stored before = Stored.of(document);
        return parseFromSource(document)
                .flatMap(reparsed -> {
                    if (!"PARSED".equals(reparsed.outcome().status())) {
                        log.warn("Re-parse document {} (profile {}, from {}): could not be parsed ({}); left as it was",
                                document.getId(), profile.getId(), reparsed.source(), reparsed.outcome().errorMessage());
                        return Mono.just(Outcome.UNREADABLE);
                    }
                    candidateProfileService.applyParseOutcome(document, reparsed.outcome());
                    return decide(profile, document, before, reparsed.source(), dryRun);
                })
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    log.warn("Re-parse document {} (profile {}): no readable file and no extracted text; left as it was",
                            document.getId(), profile.getId());
                    return Outcome.UNREADABLE;
                }));
    }

    /**
     * The stored file, read through the same storage service an upload writes to, or
     * the stored extracted_text when the file cannot be read or parsed. LOCAL files
     * written on Cloud Run went with the container, so for those rows the text is all
     * that is left.
     */
    private Mono<Reparsed> parseFromSource(CandidateResumeDocument document) {
        Mono<Reparsed> fromText = Mono.defer(() -> isNotBlank(document.getExtractedText())
                ? candidateProfileService.parseResumeText(document.getExtractedText())
                        .map(outcome -> new Reparsed("extracted text", outcome))
                : Mono.empty());

        return resumeStorageService.load(document.getUserId(), document)
                .flatMap(resource -> candidateProfileService.parseResume(resource, document.getFileExtension()))
                .map(outcome -> new Reparsed("file", outcome))
                .onErrorResume(error -> {
                    log.info("Re-parse document {}: stored file unreadable ({}); using extracted text",
                            document.getId(), error.getMessage());
                    return Mono.empty();
                })
                .flatMap(reparsed -> "PARSED".equals(reparsed.outcome().status())
                        ? Mono.just(reparsed)
                        : fromText.defaultIfEmpty(reparsed))
                .switchIfEmpty(fromText);
    }

    private Mono<Outcome> decide(
            CandidateProfile profile,
            CandidateResumeDocument document,
            Stored before,
            String source,
            boolean dryRun) {
        JsonNode previousExperience = tree(before.parsedExperience());
        JsonNode newExperience = tree(document.getParsedExperience());
        String summary = "Re-parse document %d (profile %d, from %s): jobs %d -> %d, years %s -> %s".formatted(
                document.getId(), profile.getId(), source,
                previousExperience.size(), newExperience.size(),
                experienceYears(before.parsedProfile()), experienceYears(document.getParsedProfile()));

        if (sameParse(before, document)) {
            log.info("{}; unchanged", summary);
            return Mono.just(Outcome.UNCHANGED);
        }

        ProfileChange profileChange = profileChange(profile, previousExperience, newExperience);
        String profileText = switch (profileChange) {
            case UPDATE -> dryRun ? "profile experience would be updated" : "profile experience updated";
            case KEEP_EDITED -> "profile experience kept: it is not this resume's earlier parse";
            case NONE -> "profile experience not affected";
        };

        if (dryRun) {
            log.info("{}; document would be updated; {}", summary, profileText);
            return Mono.just(Outcome.changed(profileChange));
        }

        ResumeReparseWriter.ProfileExperience profileUpdate = null;
        if (profileChange == ProfileChange.UPDATE) {
            Json previousProfileExperience = profile.getExperience();
            profile.setExperience(document.getParsedExperience());
            profileUpdate = new ResumeReparseWriter.ProfileExperience(
                    profile.getId(),
                    document.getId(),
                    document.getParsedExperience().asString(),
                    candidateProfileService.computeCompletion(profile),
                    previousProfileExperience.asString(),
                    profile.getUpdatedAt());
        }

        return writer.write(document, before.parsedProfile(), profileUpdate)
                .map(written -> {
                    if (!written) {
                        log.warn("{}; not written: the document or the profile changed during the run", summary);
                        return Outcome.CHANGED_DURING_RUN;
                    }
                    log.info("{}; document updated; {}", summary, profileText);
                    return Outcome.changed(profileChange);
                });
    }

    /**
     * What happens to the profile's experience. Like the upload, an empty parse never
     * replaces it. Beyond that it is replaced only while it is untouched: still exactly
     * this document's previous parse, or empty because that parse found nothing. A
     * candidate who fixed a title or added a job by hand has a different list, and a
     * profile filled from an earlier resume has that resume's; both are kept.
     *
     * <p>Empty next to a non-empty previous parse is kept too. Every upload since
     * resume documents were added copies a non-empty parse onto an empty profile, so
     * that profile held this parse once and the candidate cleared it.
     */
    private ProfileChange profileChange(CandidateProfile profile, JsonNode previousExperience, JsonNode newExperience) {
        if (previousExperience.equals(newExperience) || isEmpty(newExperience)) {
            return ProfileChange.NONE;
        }
        JsonNode profileExperience = tree(profile.getExperience());
        if (profileExperience.equals(newExperience)) {
            return ProfileChange.NONE;
        }
        boolean untouched = profileExperience.equals(previousExperience)
                || (isEmpty(profileExperience) && isEmpty(previousExperience));
        return untouched ? ProfileChange.UPDATE : ProfileChange.KEEP_EDITED;
    }

    /**
     * Whether the new parse is what is already stored. The parse time is left out:
     * it changes on every parse, and counting it would rewrite every document on every
     * run, so a second run could never show that the first had finished the job.
     */
    private boolean sameParse(Stored before, CandidateResumeDocument after) {
        return Objects.equals(before.parseStatus(), after.getParseStatus())
                && Objects.equals(before.extractedText(), after.getExtractedText())
                && tree(before.parsedSkills()).equals(tree(after.getParsedSkills()))
                && tree(before.parsedExperience()).equals(tree(after.getParsedExperience()))
                && tree(before.parsedEducation()).equals(tree(after.getParsedEducation()))
                && withoutParsedAt(tree(before.parsedProfile())).equals(withoutParsedAt(tree(after.getParsedProfile())));
    }

    private String experienceYears(Json parsedProfile) {
        JsonNode years = tree(parsedProfile).get("experienceYears");
        return years == null || years.isNull() ? "unknown" : years.asText();
    }

    private JsonNode withoutParsedAt(JsonNode parsedProfile) {
        if (!(parsedProfile instanceof ObjectNode object)) {
            return parsedProfile;
        }
        ObjectNode copy = object.deepCopy();
        copy.remove("parsedAt");
        return copy;
    }

    private JsonNode tree(Json json) {
        return tree(json == null ? null : json.asString());
    }

    /** Read with the same mapper on both sides, so 5.4 from jsonb and 5.4 from the parser compare equal. */
    private JsonNode tree(String json) {
        if (json == null || json.isBlank()) {
            return NullNode.getInstance();
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            return TextNode.valueOf(json);
        }
    }

    private static boolean isEmpty(JsonNode experience) {
        return experience == null || experience.isNull() || experience.isMissingNode()
                || (experience.isArray() && experience.isEmpty());
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private record Reparsed(String source, CandidateProfileService.ResumeParseOutcome outcome) {
    }

    /** A document's parse columns as they were read, before the new parse is applied. */
    private record Stored(
            String parseStatus,
            String extractedText,
            Json parsedSkills,
            Json parsedExperience,
            Json parsedEducation,
            Json parsedProfile) {

        static Stored of(CandidateResumeDocument document) {
            return new Stored(
                    document.getParseStatus(),
                    document.getExtractedText(),
                    document.getParsedSkills(),
                    document.getParsedExperience(),
                    document.getParsedEducation(),
                    document.getParsedProfile());
        }
    }

    enum DocumentResult { CHANGED, UNCHANGED, UNREADABLE, MISSING, CHANGED_DURING_RUN }

    enum ProfileChange { UPDATE, KEEP_EDITED, NONE }

    /** What happened to one active resume. */
    record Outcome(DocumentResult document, ProfileChange profile) {

        static final Outcome UNCHANGED = new Outcome(DocumentResult.UNCHANGED, ProfileChange.NONE);
        static final Outcome UNREADABLE = new Outcome(DocumentResult.UNREADABLE, ProfileChange.NONE);
        static final Outcome MISSING = new Outcome(DocumentResult.MISSING, ProfileChange.NONE);
        static final Outcome CHANGED_DURING_RUN = new Outcome(DocumentResult.CHANGED_DURING_RUN, ProfileChange.NONE);

        static Outcome changed(ProfileChange profileChange) {
            return new Outcome(DocumentResult.CHANGED, profileChange);
        }
    }

    /**
     * Totals for a run. In a dry run, documentsChanged and profilesUpdated count what
     * a real run would write.
     */
    public record Report(
            boolean dryRun,
            int activeResumes,
            int documentsChanged,
            int documentsUnchanged,
            int documentsUnreadable,
            int documentsMissing,
            int profilesUpdated,
            int profilesKeptEdited,
            int changedDuringRun) {

        static Report empty(boolean dryRun) {
            return new Report(dryRun, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        Report plus(Outcome outcome) {
            return new Report(
                    dryRun,
                    activeResumes + 1,
                    documentsChanged + count(outcome.document() == DocumentResult.CHANGED),
                    documentsUnchanged + count(outcome.document() == DocumentResult.UNCHANGED),
                    documentsUnreadable + count(outcome.document() == DocumentResult.UNREADABLE),
                    documentsMissing + count(outcome.document() == DocumentResult.MISSING),
                    profilesUpdated + count(outcome.profile() == ProfileChange.UPDATE),
                    profilesKeptEdited + count(outcome.profile() == ProfileChange.KEEP_EDITED),
                    changedDuringRun + count(outcome.document() == DocumentResult.CHANGED_DURING_RUN));
        }

        private static int count(boolean happened) {
            return happened ? 1 : 0;
        }
    }
}
