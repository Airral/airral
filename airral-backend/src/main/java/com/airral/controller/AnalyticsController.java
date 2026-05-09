package com.airral.controller;

import com.airral.dto.response.DashboardMetricsResponse;
import com.airral.dto.response.PipelineAnalyticsResponse;
import com.airral.exception.BadRequestException;
import com.airral.security.JwtTokenProvider;
import com.airral.service.AnalyticsService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/analytics")
@CrossOrigin(origins = {"http://localhost:4200", "http://localhost:4201", "http://localhost:4202", "http://localhost:4203"})
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final JwtTokenProvider jwtTokenProvider;

    public AnalyticsController(AnalyticsService analyticsService, JwtTokenProvider jwtTokenProvider) {
        this.analyticsService = analyticsService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * Get dashboard metrics
     * GET /api/analytics/dashboard
     */
    @GetMapping("/dashboard")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<DashboardMetricsResponse>> getDashboardMetrics(
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return analyticsService.getDashboardMetrics(organizationId)
                .map(ResponseEntity::ok);
    }

    /**
     * Get pipeline analytics
     * GET /api/analytics/pipeline
     */
    @GetMapping("/pipeline")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<PipelineAnalyticsResponse>> getPipelineAnalytics(
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return analyticsService.getPipelineAnalytics(organizationId)
                .map(ResponseEntity::ok);
    }

    /**
     * Helper method to extract JWT token from Authorization header
     */
    private String extractToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        throw new BadRequestException("Invalid authorization header");
    }
}
