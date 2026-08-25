package com.airral.service;

import com.airral.domain.CandidateResumeDocument;
import org.springframework.core.io.Resource;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Mono;

public interface ResumeStorageService {

    Mono<StoredResume> store(Long userId, FilePart file, String fileExtension, long maxBytes);

    Mono<Resource> load(Long userId, CandidateResumeDocument document);

    Mono<Void> delete(StoredResume storedResume);

    String downloadUrl(CandidateResumeDocument document);

    record StoredResume(
            String storageProvider,
            String storageBucket,
            String storageKey,
            String storedFileName,
            long sizeBytes) {
    }
}
