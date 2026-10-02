package com.airral.service;

import com.airral.domain.CandidateResumeDocument;
import io.r2dbc.postgresql.codec.Json;
import org.springframework.context.annotation.Profile;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * The writes of a resume re-parse: the document's parse columns and, when it is
 * still untouched, the profile's experience.
 *
 * <p>Column by column rather than through the repositories. A repository save
 * writes every column of the entity as it was read, so a candidate who edited a
 * headline while the run was between its read and its write would have had the
 * edit reverted. Each update instead names only what it changes, and only applies
 * while the row still holds what the run read; when either has moved, neither is
 * written.
 *
 * <p>Both rows change in one transaction. Written apart, a crash between them would
 * leave the document's new parse next to the profile's old one, and every later run
 * would read that profile as edited by its owner and keep the stale jobs for good.
 */
@Component
@Profile(ResumeReparseRunner.PROFILE)
public class ResumeReparseWriter {

    private final DatabaseClient databaseClient;
    private final TransactionalOperator transactions;

    public ResumeReparseWriter(DatabaseClient databaseClient, ReactiveTransactionManager transactionManager) {
        this.databaseClient = databaseClient;
        this.transactions = TransactionalOperator.create(transactionManager);
    }

    /**
     * Writes the document's new parse, and {@code profile} when it is not null.
     *
     * @return false, having written nothing, when the document's parse is no longer
     *         {@code previousParsedProfile} or the profile has changed since it was read
     */
    public Mono<Boolean> write(CandidateResumeDocument document, Json previousParsedProfile, ProfileExperience profile) {
        LocalDateTime now = LocalDateTime.now();
        DatabaseClient.GenericExecuteSpec documentUpdate = databaseClient.sql("""
                        UPDATE candidate_resume_documents
                        SET parse_status = :parseStatus,
                            parse_error = :parseError,
                            extracted_text = :extractedText,
                            parsed_skills = CAST(:parsedSkills AS jsonb),
                            parsed_experience = CAST(:parsedExperience AS jsonb),
                            parsed_education = CAST(:parsedEducation AS jsonb),
                            parsed_profile = CAST(:parsedProfile AS jsonb),
                            parsed_at = :parsedAt,
                            updated_at = :now
                        WHERE id = :id
                          AND parsed_profile = CAST(:previousParsedProfile AS jsonb)
                        """)
                .bind("parseStatus", document.getParseStatus())
                .bind("parsedSkills", document.getParsedSkills().asString())
                .bind("parsedExperience", document.getParsedExperience().asString())
                .bind("parsedEducation", document.getParsedEducation().asString())
                .bind("parsedProfile", document.getParsedProfile().asString())
                .bind("now", now)
                .bind("id", document.getId())
                .bind("previousParsedProfile", previousParsedProfile.asString());
        documentUpdate = bindNullable(documentUpdate, "parseError", document.getParseError(), String.class);
        documentUpdate = bindNullable(documentUpdate, "extractedText", document.getExtractedText(), String.class);
        documentUpdate = bindNullable(documentUpdate, "parsedAt", document.getParsedAt(), LocalDateTime.class);
        Mono<Boolean> documentWrite = documentUpdate.fetch().rowsUpdated().map(rows -> rows == 1);

        Mono<Boolean> bothWrites = documentWrite.flatMap(written -> {
            if (!written) {
                return Mono.just(false);
            }
            if (profile == null) {
                return Mono.just(true);
            }
            return writeProfile(profile, now).flatMap(profileWritten -> profileWritten
                    ? Mono.just(true)
                    : Mono.error(new RowChanged()));
        });

        return transactions.transactional(bothWrites)
                .onErrorResume(RowChanged.class, changed -> Mono.just(false));
    }

    private Mono<Boolean> writeProfile(ProfileExperience profile, LocalDateTime now) {
        DatabaseClient.GenericExecuteSpec profileUpdate = databaseClient.sql("""
                        UPDATE candidate_profiles
                        SET experience = CAST(:experience AS jsonb),
                            profile_completion = :profileCompletion,
                            updated_at = :now
                        WHERE id = :id
                          AND active_resume_document_id = :documentId
                          AND experience = CAST(:previousExperience AS jsonb)
                          AND updated_at IS NOT DISTINCT FROM CAST(:previousUpdatedAt AS timestamp)
                        """)
                .bind("experience", profile.experience())
                .bind("profileCompletion", profile.profileCompletion())
                .bind("now", now)
                .bind("id", profile.profileId())
                .bind("documentId", profile.documentId())
                .bind("previousExperience", profile.previousExperience());
        profileUpdate = bindNullable(profileUpdate, "previousUpdatedAt", profile.previousUpdatedAt(), LocalDateTime.class);
        return profileUpdate.fetch().rowsUpdated().map(rows -> rows == 1);
    }

    private static DatabaseClient.GenericExecuteSpec bindNullable(
            DatabaseClient.GenericExecuteSpec spec, String name, Object value, Class<?> type) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }

    /**
     * The profile's new experience, and what it held when it was read. The update
     * applies only while both the experience and updated_at are unchanged, so an edit
     * the candidate saves during the run is never overwritten.
     */
    public record ProfileExperience(
            Long profileId,
            Long documentId,
            String experience,
            int profileCompletion,
            String previousExperience,
            LocalDateTime previousUpdatedAt) {
    }

    /** Rolls back the document write when the profile changed under the run. */
    private static final class RowChanged extends RuntimeException {
        RowChanged() {
            super("The profile changed during the re-parse", null, false, false);
        }
    }
}
