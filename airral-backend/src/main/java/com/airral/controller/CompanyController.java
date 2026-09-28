package com.airral.controller;

import com.airral.dto.request.UpdateCompanyProfileRequest;
import com.airral.dto.response.CompanyProfileResponse;
import com.airral.exception.BadRequestException;
import com.airral.security.JwtTokenProvider;
import com.airral.service.CompanyProfileService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** The caller's own company profile. HR managers change it; the rest of the team reads it. */
@RestController
@RequestMapping("/api/company")
public class CompanyController {

    private final CompanyProfileService profiles;
    private final JwtTokenProvider jwtTokenProvider;

    public CompanyController(CompanyProfileService profiles, JwtTokenProvider jwtTokenProvider) {
        this.profiles = profiles;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'MANAGER', 'EMPLOYEE', 'ADMIN')")
    public Mono<ResponseEntity<CompanyProfileResponse>> get(@RequestHeader("Authorization") String authHeader) {
        return profiles.get(companyOf(authHeader)).map(ResponseEntity::ok);
    }

    @PutMapping
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<CompanyProfileResponse>> update(
            @Valid @RequestBody UpdateCompanyProfileRequest request,
            @RequestHeader("Authorization") String authHeader) {
        return profiles.update(companyOf(authHeader), request).map(ResponseEntity::ok);
    }

    private Long companyOf(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new BadRequestException("Invalid authorization header");
        }
        return jwtTokenProvider.getOrganizationIdFromToken(authHeader.substring(7));
    }
}
