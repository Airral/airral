package com.airral.service;

import com.airral.domain.CandidateResumeDocument;
import com.airral.exception.BadRequestException;
import com.airral.exception.NotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class LocalResumeStorageService implements ResumeStorageService {

    private static final String PROVIDER = "LOCAL";
    private static final String RESUME_DIRECTORY = "candidate-resumes";

    private final Path storageRoot;
    private final Path resumeRoot;

    public LocalResumeStorageService(@Value("${file.upload.storage-path:${user.home}/.airral/uploads}") String storagePath) {
        this.storageRoot = Path.of(storagePath).toAbsolutePath().normalize();
        this.resumeRoot = storageRoot.resolve(RESUME_DIRECTORY).normalize();
    }

    @Override
    public Mono<StoredResume> store(Long userId, FilePart file, String fileExtension, long maxBytes) {
        return Mono.fromCallable(() -> {
                    String extension = fileExtension.startsWith(".") ? fileExtension : "." + fileExtension;
                    Path userDirectory = resumeRoot.resolve(String.valueOf(userId)).normalize();
                    if (!userDirectory.startsWith(resumeRoot)) {
                        throw new BadRequestException("Invalid resume storage path");
                    }

                    Files.createDirectories(userDirectory);
                    String storedFileName = "resume-" + System.currentTimeMillis() + extension;
                    Path targetPath = userDirectory.resolve(storedFileName).normalize();
                    if (!targetPath.startsWith(userDirectory)) {
                        throw new BadRequestException("Invalid resume storage path");
                    }
                    return new LocalResumeTarget(targetPath, storageRoot.relativize(targetPath).toString().replace('\\', '/'), storedFileName);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(target -> file.transferTo(target.path())
                        .then(Mono.fromCallable(() -> {
                                    long size = Files.size(target.path());
                                    if (size > maxBytes) {
                                        Files.deleteIfExists(target.path());
                                        throw new BadRequestException("Resume file is larger than the allowed size");
                                    }

                                    return new StoredResume(PROVIDER, null, target.storageKey(), target.storedFileName(), size);
                                })
                                .subscribeOn(Schedulers.boundedElastic())));
    }

    @Override
    public Mono<Resource> load(Long userId, CandidateResumeDocument document) {
        return Mono.fromCallable(() -> {
            if (document == null || document.getStorageKey() == null || document.getStorageKey().isBlank()) {
                throw new NotFoundException("Resume not found");
            }
            if (!userId.equals(document.getUserId())) {
                throw new NotFoundException("Resume not found");
            }
            if (!PROVIDER.equalsIgnoreCase(document.getStorageProvider())) {
                throw new BadRequestException("Unsupported resume storage provider");
            }

            Path filePath = storageRoot.resolve(document.getStorageKey()).normalize();
            if (!filePath.startsWith(storageRoot) || !Files.exists(filePath)) {
                throw new NotFoundException("Resume not found");
            }
            return (Resource) new FileSystemResource(filePath);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> delete(StoredResume storedResume) {
        return Mono.fromRunnable(() -> {
            if (storedResume == null || storedResume.storageKey() == null || storedResume.storageKey().isBlank()) {
                return;
            }
            Path filePath = storageRoot.resolve(storedResume.storageKey()).normalize();
            if (filePath.startsWith(storageRoot)) {
                try {
                    Files.deleteIfExists(filePath);
                } catch (Exception ignored) {
                    // Best-effort cleanup after a failed upload transaction.
                }
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public String downloadUrl(CandidateResumeDocument document) {
        return document == null || document.getId() == null
                ? null
                : "/api/candidate/profile/resume/document/" + document.getId();
    }

    private record LocalResumeTarget(Path path, String storageKey, String storedFileName) {
    }
}
