package com.airral.controller;

import com.airral.exception.BadRequestException;
import com.airral.service.CompanyVerificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Platform-admin review of employer companies.
 *
 * <p>A company whose people cannot prove an address on its own domain -- a
 * free-mail sign-up, or no domain at all -- waits here as PENDING, and its jobs
 * stay out of the candidate catalogue until an admin approves it. Behind
 * {@code /api/admin/**}, which SecurityConfig limits to ADMIN.
 */
@RestController
@RequestMapping("/api/admin/companies")
public class CompanyReviewAdminController {

    private static final Set<String> STATUSES = Set.of(
            CompanyVerificationService.PENDING, CompanyVerificationService.VERIFIED, CompanyVerificationService.REJECTED);

    private final DatabaseClient databaseClient;
    private final CompanyVerificationService companyVerificationService;

    public CompanyReviewAdminController(DatabaseClient databaseClient,
                                        CompanyVerificationService companyVerificationService) {
        this.databaseClient = databaseClient;
        this.companyVerificationService = companyVerificationService;
    }

    public record ReviewRequest(String note) {}

    /**
     * Companies in one review state, newest first, with what an admin needs to
     * decide: who signed up, whether that person proved their address, and how
     * many jobs are waiting on the decision.
     */
    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> list(
            @RequestParam(value = "status", defaultValue = "PENDING") String status) {
        String wanted = status.trim().toUpperCase();
        if (!STATUSES.contains(wanted)) {
            return Mono.error(new BadRequestException("status must be PENDING, VERIFIED or REJECTED"));
        }
        Flux<Map<String, Object>> rows = databaseClient.sql("""
                        SELECT o.id, o.name, o.domain, o.verification_status, o.verification_method,
                               o.verification_note, o.verified_at, o.created_at,
                               contact.email          AS contact_email,
                               contact.email_verified AS contact_verified,
                               (SELECT COUNT(*) FROM jobs j
                                 WHERE j.organization_id = o.id AND j.status = 'OPEN') AS open_jobs
                        FROM organizations o
                        LEFT JOIN LATERAL (
                            SELECT u.email, u.email_verified
                            FROM users u
                            WHERE u.organization_id = o.id
                            ORDER BY (u.role = 'HR_MANAGER') DESC, u.created_at ASC
                            LIMIT 1
                        ) contact ON true
                        WHERE o.verification_status = :status
                        ORDER BY o.created_at DESC
                        LIMIT 200
                        """)
                .bind("status", wanted)
                .map((row, meta) -> {
                    Map<String, Object> company = new LinkedHashMap<>();
                    company.put("id", row.get("id", Long.class));
                    company.put("name", row.get("name", String.class));
                    company.put("domain", row.get("domain", String.class));
                    company.put("verificationStatus", row.get("verification_status", String.class));
                    company.put("verificationMethod", row.get("verification_method", String.class));
                    company.put("verificationNote", row.get("verification_note", String.class));
                    company.put("verifiedAt", row.get("verified_at", LocalDateTime.class));
                    company.put("createdAt", row.get("created_at", LocalDateTime.class));
                    company.put("contactEmail", row.get("contact_email", String.class));
                    company.put("contactVerified", Boolean.TRUE.equals(row.get("contact_verified", Boolean.class)));
                    company.put("openJobs", row.get("open_jobs", Long.class));
                    return company;
                })
                .all();
        return rows.collectList()
                .map(list -> ResponseEntity.ok(Map.<String, Object>of("status", wanted, "companies", list)));
    }

    /** Approve: the company's open jobs are published to candidates immediately. */
    @PostMapping("/{id}/approve")
    public Mono<ResponseEntity<Map<String, Object>>> approve(@PathVariable Long id,
                                                             @RequestBody(required = false) ReviewRequest body) {
        return companyVerificationService.approve(id, body == null ? null : body.note())
                .map(org -> ResponseEntity.ok(Map.<String, Object>of(
                        "id", org.getId(), "verificationStatus", org.getVerificationStatus())));
    }

    /** Reject: any of the company's jobs are taken out of the candidate catalogue. */
    @PostMapping("/{id}/reject")
    public Mono<ResponseEntity<Map<String, Object>>> reject(@PathVariable Long id,
                                                            @RequestBody(required = false) ReviewRequest body) {
        return companyVerificationService.reject(id, body == null ? null : body.note())
                .map(org -> ResponseEntity.ok(Map.<String, Object>of(
                        "id", org.getId(), "verificationStatus", org.getVerificationStatus())));
    }
}
