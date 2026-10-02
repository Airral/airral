package com.airral.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * One-shot re-parse of stored resumes, for the {@code reparse-resumes} profile.
 *
 * <p>Resumes uploaded before v1.28.5 keep the parse they got then, and most of those
 * read as 0 jobs and 0.0 years. This re-parses each profile's active resume with the
 * current parser ({@link ResumeReparseService}) and exits. It is a dry run unless
 * {@code airral.resume-reparse.apply=true} is passed: every document still gets its
 * log line -- id, old jobs and years, new jobs and years, and what would be written --
 * but nothing is. Running it again after a real run should report every document
 * unchanged.
 *
 * <p>Nothing schedules it. To run it against production, from a checkout of the commit
 * that is deployed (Flyway migrates on every start, so an older or newer checkout would
 * move the schema), while the database is up (08:00-20:00 ET), signed in with
 * {@code gcloud auth application-default login} as an account that can connect to Cloud
 * SQL, read the two secrets and read the resume bucket. DB_NAME, DB_USER and
 * RESUME_BUCKET are the repository variables the deploy workflow uses:
 *
 * <pre>
 * cd airral-backend &amp;&amp; ./gradlew bootJar -x test
 * export SPRING_PROFILES_ACTIVE=gcp,reparse-resumes
 * export CLOUD_SQL_INSTANCE="$(gcloud sql instances describe airral-db --format='value(connectionName)')"
 * export DB_NAME=... DB_USER=...
 * export FILE_UPLOAD_STORAGE_PROVIDER=GCS FILE_UPLOAD_GCS_BUCKET=...
 * export DB_PASSWORD="$(gcloud secrets versions access latest --secret=db-password)"
 * export JWT_ENCRYPTION_SECRET="$(gcloud secrets versions access latest --secret=jwt-encryption-secret)"
 * java -jar build/libs/*.jar 2&gt;&amp;1 | tee reparse-dry-run.log
 * java -jar build/libs/*.jar --airral.resume-reparse.apply=true 2&gt;&amp;1 | tee reparse.log
 * </pre>
 *
 * <p>Read the dry run before the real one. The GCS settings matter: without them a
 * GCS-stored resume cannot be loaded and is re-parsed from its stored text instead.
 * The JWT secret is checked at startup even though a batch issues no tokens.
 */
@Component
@Profile(ResumeReparseRunner.PROFILE)
public class ResumeReparseRunner implements ApplicationRunner {

    static final String PROFILE = "reparse-resumes";

    private static final Logger log = LoggerFactory.getLogger(ResumeReparseRunner.class);

    private final ResumeReparseService resumeReparseService;
    private final ApplicationContext applicationContext;
    private final boolean apply;
    private final Duration timeout;

    public ResumeReparseRunner(
            ResumeReparseService resumeReparseService,
            ApplicationContext applicationContext,
            @Value("${airral.resume-reparse.apply:false}") boolean apply,
            @Value("${airral.resume-reparse.timeout-minutes:30}") int timeoutMinutes) {
        this.resumeReparseService = resumeReparseService;
        this.applicationContext = applicationContext;
        this.apply = apply;
        this.timeout = Duration.ofMinutes(Math.max(1, timeoutMinutes));
    }

    /**
     * Re-parses, then ends the process itself rather than returning to Spring Boot.
     * Returning leaves the JVM to exit only once its last non-daemon thread has, and
     * the sync runner, which returns, was found never to exit. {@link
     * SpringApplication#exit} closes the context first, so the connection pool is shut
     * down before the exit code is handed back.
     */
    @Override
    public void run(ApplicationArguments args) {
        int exitCode = reparse();
        System.exit(SpringApplication.exit(applicationContext, () -> exitCode));
    }

    /** The re-parse, as an exit code: 0 when it ran to the end, 1 when it failed. */
    int reparse() {
        boolean dryRun = !apply;
        log.info("Starting resume re-parse{} (timeout {})",
                dryRun ? " as a DRY RUN; pass --airral.resume-reparse.apply=true to write" : "", timeout);
        try {
            ResumeReparseService.Report report = resumeReparseService.reparse(dryRun).block(timeout);
            if (report == null) {
                throw new IllegalStateException("Resume re-parse returned no result");
            }
            log.info("Resume re-parse finished{}: active resumes={}, documents {}={}, unchanged={}, unreadable={},"
                            + " missing={}, changed during run={}; profiles {}={}, kept because edited={}",
                    dryRun ? " (DRY RUN, nothing written)" : "",
                    report.activeResumes(),
                    dryRun ? "to update" : "updated", report.documentsChanged(),
                    report.documentsUnchanged(),
                    report.documentsUnreadable(),
                    report.documentsMissing(),
                    report.changedDuringRun(),
                    dryRun ? "to update" : "updated", report.profilesUpdated(),
                    report.profilesKeptEdited());
            return 0;
        } catch (RuntimeException e) {
            log.error("Resume re-parse failed", e);
            return 1;
        }
    }
}
