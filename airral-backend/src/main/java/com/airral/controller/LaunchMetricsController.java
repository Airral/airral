package com.airral.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.airral.service.LaunchMetricsService;

import reactor.core.publisher.Mono;

/**
 * The admin portal's Launch page. ADMIN only: it names the people who signed
 * up. Under /api/admin/**, which SecurityConfig also limits to ADMIN; the
 * annotation keeps it shut if that rule is ever narrowed.
 */
@RestController
@RequestMapping("/api/admin/analytics")
public class LaunchMetricsController {

    private final LaunchMetricsService launchMetricsService;

    public LaunchMetricsController(LaunchMetricsService launchMetricsService) {
        this.launchMetricsService = launchMetricsService;
    }

    @GetMapping("/launch")
    @PreAuthorize("hasAuthority('ADMIN')")
    public Mono<ResponseEntity<LaunchMetricsService.LaunchMetrics>> launch(
            @RequestParam(value = "days", defaultValue = "7") int days) {
        return launchMetricsService.summarize(days).map(ResponseEntity::ok);
    }
}
