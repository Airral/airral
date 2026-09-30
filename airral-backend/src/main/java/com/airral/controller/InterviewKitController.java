package com.airral.controller;

import com.airral.dto.request.InterviewKitRequest;
import com.airral.dto.response.InterviewKitResponse;
import com.airral.exception.BadRequestException;
import com.airral.security.JwtTokenProvider;
import com.airral.service.InterviewKitService;
import jakarta.validation.Valid;
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

/**
 * A company's interview kits. HR managers keep them; hiring managers read
 * them to pick one for a job.
 */
@RestController
@RequestMapping("/api/interview-kits")
public class InterviewKitController {

    private final InterviewKitService kits;
    private final JwtTokenProvider jwtTokenProvider;

    public InterviewKitController(InterviewKitService kits, JwtTokenProvider jwtTokenProvider) {
        this.kits = kits;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<Flux<InterviewKitResponse>>> list(@RequestHeader("Authorization") String authHeader) {
        return Mono.just(ResponseEntity.ok(kits.list(companyOf(authHeader))));
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<InterviewKitResponse>> create(@Valid @RequestBody InterviewKitRequest request,
                                                             @RequestHeader("Authorization") String authHeader) {
        return kits.create(companyOf(authHeader), request)
                .map(kit -> ResponseEntity.status(HttpStatus.CREATED).body(kit));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<InterviewKitResponse>> update(@PathVariable Long id,
                                                             @Valid @RequestBody InterviewKitRequest request,
                                                             @RequestHeader("Authorization") String authHeader) {
        return kits.update(id, companyOf(authHeader), request).map(ResponseEntity::ok);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<Void>> delete(@PathVariable Long id, @RequestHeader("Authorization") String authHeader) {
        return kits.delete(id, companyOf(authHeader)).thenReturn(ResponseEntity.noContent().<Void>build());
    }

    private Long companyOf(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new BadRequestException("Invalid authorization header");
        }
        return jwtTokenProvider.getOrganizationIdFromToken(authHeader.substring(7));
    }
}
