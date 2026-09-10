package com.airral.service;

import com.airral.domain.CandidateResumeDocument;
import com.airral.exception.BadRequestException;
import com.airral.exception.NotFoundException;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.storage.Storage;
import com.google.api.services.storage.StorageScopes;
import com.google.api.services.storage.model.StorageObject;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;

/**
 * Resume storage backed by a Google Cloud Storage bucket.
 *
 * <p>The local implementation writes under {@code file.upload.storage-path}, which on Cloud Run is
 * the container filesystem: an in-memory tmpfs. Every uploaded resume was therefore charged against
 * the service's 1Gi memory limit, was lost on every revision, restart and scale-down, and was
 * visible only to the instance that happened to receive the upload -- with min-instances 1 and
 * max-instances 3 a later download succeeds or 404s depending on which instance answers. Resumes
 * are the most sensitive data this product holds, so they belong in durable, shared, IAM-controlled
 * storage instead.
 *
 * <p>Only the transport changes. The object name is the same relative path the local implementation
 * used ({@code candidate-resumes/{userId}/resume-{millis}.{ext}}), so the existing
 * {@code storage_key} column carries it unchanged and
 * {@code CandidateResumeDocumentRepository.findByStoredFileName}, which matches on
 * {@code '%/' || storedFileName}, keeps working. No migration is needed.
 */
@Service
// Selected by configuration, LOCAL by default, so a developer with no GCP credentials still works.
// @Primary because LocalResumeStorageService stays registered even when this bean is active: it is
// the reader for rows that were written before the switch (see load below), so the two beans
// coexist and the injection point needs to be told which one is the writer.
@Primary
@ConditionalOnProperty(prefix = "file.upload", name = "storage-provider", havingValue = "GCS")
public class GcsResumeStorageService implements ResumeStorageService {

    private static final Logger log = LoggerFactory.getLogger(GcsResumeStorageService.class);

    private static final String PROVIDER = "GCS";
    private static final String RESUME_PREFIX = "candidate-resumes";
    private static final String APPLICATION_NAME = "airral-api";
    private static final String DOCX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final LocalResumeStorageService localResumeStorageService;
    private final String bucket;
    private final Storage storage;

    public GcsResumeStorageService(
            LocalResumeStorageService localResumeStorageService,
            @Value("${file.upload.gcs-bucket:}") String bucket) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException(
                    "file.upload.gcs-bucket must be set when file.upload.storage-provider is GCS");
        }
        this.localResumeStorageService = localResumeStorageService;
        this.bucket = bucket.trim();
        this.storage = buildStorageClient();
        // The only signal that the switch actually took. Losing FILE_UPLOAD_STORAGE_PROVIDER (the
        // deploy workflow uses --set-env-vars, which replaces the whole environment) leaves this
        // bean out of the context and silently reinstates local-disk storage, which is a valid
        // configuration and so reports nothing of its own. One line to grep for after a deploy.
        log.info("Resume storage provider is GCS, bucket {}", this.bucket);
    }

    @Override
    public Mono<StoredResume> store(Long userId, FilePart file, String fileExtension, long maxBytes) {
        String extension = fileExtension.startsWith(".") ? fileExtension : "." + fileExtension;
        String storedFileName = "resume-" + System.currentTimeMillis() + extension;
        String objectName = objectPrefix(userId) + storedFileName;

        return readWithinLimit(file, maxBytes)
                .flatMap(bytes -> Mono.fromCallable(() -> {
                            StorageObject written = storage.objects()
                                    .insert(bucket,
                                            new StorageObject().setName(objectName),
                                            new ByteArrayContent(contentTypeFor(extension), bytes))
                                    .setName(objectName)
                                    .execute();
                            long size = written.getSize() == null ? bytes.length : written.getSize().longValue();
                            return new StoredResume(PROVIDER, bucket, objectName, storedFileName, size);
                        })
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    @Override
    public Mono<Resource> load(Long userId, CandidateResumeDocument document) {
        // A row's storage_provider decides who reads it, not whichever bean happens to be active.
        // Rows written before this switch say LOCAL and the download path still has to try them,
        // rather than answering 400 "unsupported provider". On Cloud Run those files went with the
        // container that wrote them, so this resolves to an honest 404 instead of a misleading 400.
        if (document != null && !PROVIDER.equalsIgnoreCase(document.getStorageProvider())) {
            return localResumeStorageService.load(userId, document);
        }

        return Mono.fromCallable(() -> {
            if (document == null || document.getStorageKey() == null || document.getStorageKey().isBlank()) {
                throw new NotFoundException("Resume not found");
            }
            if (!userId.equals(document.getUserId())) {
                throw new NotFoundException("Resume not found");
            }
            // Same containment check the local implementation makes against its storage root: a key
            // that does not sit under this user's own prefix is not this user's resume.
            if (!document.getStorageKey().startsWith(objectPrefix(userId))) {
                throw new NotFoundException("Resume not found");
            }

            try (InputStream content = storage.objects()
                    .get(bucketFor(document), document.getStorageKey())
                    .executeMediaAsInputStream()) {
                // Read whole rather than streamed: the caller digests and parses the same Resource
                // twice (Mono.zip of sha256 and parse), so it has to be re-readable. Bounded by the
                // upload limit, 5MB by config.
                return (Resource) new ByteArrayResource(content.readAllBytes());
            } catch (HttpResponseException ex) {
                if (ex.getStatusCode() == 404) {
                    throw new NotFoundException("Resume not found");
                }
                throw ex;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> delete(StoredResume storedResume) {
        if (storedResume == null || storedResume.storageKey() == null || storedResume.storageKey().isBlank()) {
            return Mono.empty();
        }
        if (!PROVIDER.equalsIgnoreCase(storedResume.storageProvider())) {
            return localResumeStorageService.delete(storedResume);
        }

        String targetBucket = storedResume.storageBucket() == null || storedResume.storageBucket().isBlank()
                ? bucket
                : storedResume.storageBucket();
        return Mono.fromRunnable(() -> {
            try {
                storage.objects().delete(targetBucket, storedResume.storageKey()).execute();
            } catch (Exception ex) {
                // Best-effort cleanup after a failed upload transaction, same contract as the
                // local implementation, but reported rather than swallowed: a leaked object is a
                // different thing here. A local orphan dies with the container; a bucket orphan is
                // a candidate's resume kept indefinitely with no row pointing at it, so the only
                // way anyone finds out is this line.
                log.warn("Could not delete orphaned resume object gs://{}/{}",
                        targetBucket, storedResume.storageKey(), ex);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public String downloadUrl(CandidateResumeDocument document) {
        return document == null || document.getId() == null
                ? null
                : "/api/candidate/profile/resume/document/" + document.getId();
    }

    /**
     * The local implementation writes the file and deletes it again once it turns out to be
     * over-size. An object store has no equally cheap undo -- the delete is a second billed request
     * that can itself fail and leave the over-size object behind -- so the limit is enforced before
     * anything is written. Reading maxBytes into memory is affordable because WebFlux has already
     * spooled anything over spring.webflux.multipart.max-in-memory-size (256KB) to disk, and the
     * ceiling is the configured 5MB per upload in flight.
     */
    private Mono<byte[]> readWithinLimit(FilePart file, long maxBytes) {
        int limit = (int) Math.min(maxBytes + 1, Integer.MAX_VALUE);
        return DataBufferUtils.join(file.content(), limit)
                .map(buffer -> {
                    try {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        return bytes;
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                })
                // join() completes empty for a part with no content. The local implementation stores
                // a zero-byte file in that case, and the upload must not silently return nothing.
                .defaultIfEmpty(new byte[0])
                .flatMap(bytes -> bytes.length > maxBytes
                        ? Mono.error(new BadRequestException("Resume file is larger than the allowed size"))
                        : Mono.just(bytes))
                .onErrorMap(DataBufferLimitException.class,
                        ex -> new BadRequestException("Resume file is larger than the allowed size"));
    }

    /**
     * Built once at startup. The client is stateless and thread-safe, and resolving Application
     * Default Credentials here turns a misconfigured deployment into a legible startup failure
     * rather than a 500 on some candidate's first upload. ADC only: the Cloud Run service account
     * via the metadata server, because this project authenticates through Workload Identity and
     * deliberately ships no service-account JSON key.
     */
    private static Storage buildStorageClient() {
        try {
            GoogleCredentials credentials = GoogleCredentials.getApplicationDefault();
            if (credentials.createScopedRequired()) {
                credentials = credentials.createScoped(StorageScopes.DEVSTORAGE_READ_WRITE);
            }
            return new Storage.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(),
                    new HttpCredentialsAdapter(credentials))
                    .setApplicationName(APPLICATION_NAME)
                    .build();
        } catch (GeneralSecurityException | IOException ex) {
            throw new IllegalStateException("Unable to initialise Google Cloud Storage resume storage", ex);
        }
    }

    private String bucketFor(CandidateResumeDocument document) {
        // Prefer the bucket recorded on the row, which is what the storage_bucket column is for: a
        // later bucket change must not orphan everything uploaded before it.
        return document.getStorageBucket() == null || document.getStorageBucket().isBlank()
                ? bucket
                : document.getStorageBucket();
    }

    private static String objectPrefix(Long userId) {
        return RESUME_PREFIX + "/" + userId + "/";
    }

    private static String contentTypeFor(String extension) {
        return ".pdf".equalsIgnoreCase(extension) ? "application/pdf" : DOCX_CONTENT_TYPE;
    }
}
