package com.airral.controller;

import com.airral.dto.request.CandidateJobFitRequest;
import com.airral.dto.request.SaveCandidateJobRequest;
import com.airral.dto.request.UpdateCandidateSavedJobRequest;
import com.airral.dto.response.CandidateJobFitResponse;
import com.airral.dto.response.CandidateSavedJobResponse;
import com.airral.exception.BadRequestException;
import com.airral.security.JwtTokenProvider;
import com.airral.service.CandidateJobWorkspaceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/candidate")
@PreAuthorize("hasAnyAuthority('APPLICANT', 'ADMIN')")
public class CandidateJobWorkspaceController {

    private final CandidateJobWorkspaceService workspaceService;
    private final JwtTokenProvider jwtTokenProvider;

    public CandidateJobWorkspaceController(CandidateJobWorkspaceService workspaceService, JwtTokenProvider jwtTokenProvider) {
        this.workspaceService = workspaceService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @GetMapping("/saved-jobs")
    public Mono<ResponseEntity<Flux<CandidateSavedJobResponse>>> listSavedJobs(
            @RequestHeader("Authorization") String authHeader) {
        return Mono.just(ResponseEntity.ok(workspaceService.listSavedJobs(email(authHeader))));
    }

    @PostMapping("/saved-jobs")
    public Mono<ResponseEntity<CandidateSavedJobResponse>> saveJob(
            @RequestBody SaveCandidateJobRequest request,
            @RequestHeader("Authorization") String authHeader) {
        return workspaceService.saveJob(email(authHeader), request)
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }

    @PutMapping("/saved-jobs/{id}")
    public Mono<ResponseEntity<CandidateSavedJobResponse>> updateSavedJob(
            @PathVariable Long id,
            @RequestBody UpdateCandidateSavedJobRequest request,
            @RequestHeader("Authorization") String authHeader) {
        return workspaceService.updateSavedJob(email(authHeader), id, request)
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/saved-jobs/{id}")
    public Mono<ResponseEntity<Void>> deleteSavedJob(
            @PathVariable Long id,
            @RequestHeader("Authorization") String authHeader) {
        return workspaceService.deleteSavedJob(email(authHeader), id)
                .thenReturn(ResponseEntity.<Void>noContent().build());
    }

    @PostMapping("/job-fit")
    public Mono<ResponseEntity<CandidateJobFitResponse>> runJobFit(
            @RequestBody CandidateJobFitRequest request,
            @RequestHeader("Authorization") String authHeader) {
        return workspaceService.runFit(email(authHeader), request)
                .map(ResponseEntity::ok);
    }

    private String email(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return jwtTokenProvider.getEmailFromToken(authHeader.substring(7));
        }
        throw new BadRequestException("Invalid authorization header");
    }
}
