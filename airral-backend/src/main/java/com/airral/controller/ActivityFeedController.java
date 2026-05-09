package com.airral.controller;

import com.airral.dto.response.ActivityFeedResponse;
import com.airral.exception.BadRequestException;
import com.airral.security.JwtTokenProvider;
import com.airral.service.ActivityFeedService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/activity")
@CrossOrigin(origins = {"http://localhost:4200", "http://localhost:4201", "http://localhost:4202", "http://localhost:4203"})
public class ActivityFeedController {

    private final ActivityFeedService activityFeedService;
    private final JwtTokenProvider jwtTokenProvider;

    public ActivityFeedController(ActivityFeedService activityFeedService, JwtTokenProvider jwtTokenProvider) {
        this.activityFeedService = activityFeedService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * Get recent activities
     * GET /api/activity?limit=100
     */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'MANAGER', 'EMPLOYEE', 'ADMIN')")
    public Mono<ResponseEntity<Flux<ActivityFeedResponse>>> getRecentActivities(
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return Mono.just(ResponseEntity.ok(activityFeedService.getRecentActivities(organizationId, limit)));
    }

    /**
     * Get activities by type
     * GET /api/activity/type/{activityType}?limit=50
     */
    @GetMapping("/type/{activityType}")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'MANAGER', 'EMPLOYEE', 'ADMIN')")
    public Mono<ResponseEntity<Flux<ActivityFeedResponse>>> getActivitiesByType(
            @PathVariable String activityType,
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return Mono.just(ResponseEntity.ok(activityFeedService.getActivitiesByType(organizationId, activityType, limit)));
    }

    /**
     * Get activities by category
     * GET /api/activity/category/{category}?limit=50
     */
    @GetMapping("/category/{category}")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'MANAGER', 'EMPLOYEE', 'ADMIN')")
    public Mono<ResponseEntity<Flux<ActivityFeedResponse>>> getActivitiesByCategory(
            @PathVariable String category,
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return Mono.just(ResponseEntity.ok(activityFeedService.getActivitiesByCategory(organizationId, category, limit)));
    }

    /**
     * Get activities since a time
     * GET /api/activity/since?hours=24
     */
    @GetMapping("/since")
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'MANAGER', 'EMPLOYEE', 'ADMIN')")
    public Mono<ResponseEntity<Flux<ActivityFeedResponse>>> getActivitiesSince(
            @RequestParam(defaultValue = "24") int hours,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        LocalDateTime since = LocalDateTime.now().minusHours(hours);
        return Mono.just(ResponseEntity.ok(activityFeedService.getActivitiesSince(organizationId, since)));
    }

    /**
     * Get public activities
     * GET /api/activity/public?limit=50
     */
    @GetMapping("/public")
    public Mono<ResponseEntity<Flux<ActivityFeedResponse>>> getPublicActivities(
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader("Authorization") String authHeader) {
        
        String token = extractToken(authHeader);
        Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(token);

        return Mono.just(ResponseEntity.ok(activityFeedService.getPublicActivities(organizationId, limit)));
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
