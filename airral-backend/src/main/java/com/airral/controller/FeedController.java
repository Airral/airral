package com.airral.controller;

import com.airral.dto.request.CreateFeedPostRequest;
import com.airral.dto.request.FeedReactionRequest;
import com.airral.dto.response.FeedPageResponse;
import com.airral.dto.response.FeedPostResponse;
import com.airral.dto.response.FeedSignalPageResponse;
import com.airral.dto.response.NewsPageResponse;
import com.airral.exception.BadRequestException;
import com.airral.security.JwtTokenProvider;
import com.airral.service.FeedSignalService;
import com.airral.service.FeedService;
import com.airral.service.NewsFeedService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/feed")
public class FeedController {

    private final FeedService feedService;
    private final FeedSignalService feedSignalService;
    private final NewsFeedService newsFeedService;
    private final JwtTokenProvider jwtTokenProvider;

    public FeedController(
            FeedService feedService,
            FeedSignalService feedSignalService,
            NewsFeedService newsFeedService,
            JwtTokenProvider jwtTokenProvider) {
        this.feedService = feedService;
        this.feedSignalService = feedSignalService;
        this.newsFeedService = newsFeedService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * GET /api/feed?page=1&size=10
     * Public feed — no auth required. If auth header present, viewerReaction is populated.
     */
    @GetMapping
    public Mono<ResponseEntity<FeedPageResponse>> getPublicFeed(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Long userId = resolveUserId(authHeader);
        return feedService.getPublicFeed(page, size, userId)
                .map(ResponseEntity::ok);
    }

    /**
     * GET /api/feed/signals?size=12&q=software%20funding
     * Public career signal feed backed by external company/news data.
     */
    @GetMapping("/signals")
    public Mono<ResponseEntity<FeedSignalPageResponse>> getSignalFeed(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "12") int size) {
        return feedSignalService.getSignals(q, size)
                .map(ResponseEntity::ok);
    }

    /**
     * GET /api/feed/news?category=TECH&size=30
     * Public normalized news feed for the applicant portal. The frontend consumes
     * AIRRAL's model while the backend owns provider queries, deduping, and source attribution.
     */
    @GetMapping("/news")
    public Mono<ResponseEntity<NewsPageResponse>> getNewsFeed(
            @RequestParam(defaultValue = "TECH") String category,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "30") int size) {
        return newsFeedService.getNews(category, q, size)
                .map(ResponseEntity::ok);
    }

    /**
     * POST /api/feed
     * Create a company post. Requires HR_MANAGER or ADMIN role.
     */
    @PostMapping
    @PreAuthorize("hasAnyAuthority('HR_MANAGER', 'ADMIN')")
    public Mono<ResponseEntity<FeedPostResponse>> createPost(
            @RequestBody CreateFeedPostRequest request,
            @RequestHeader("Authorization") String authHeader) {

        String token = extractToken(authHeader);
        Long orgId = jwtTokenProvider.getOrganizationIdFromToken(token);
        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        return feedService.createPost(orgId, userId, request)
                .map(post -> ResponseEntity.status(HttpStatus.CREATED).body(post));
    }

    /**
     * POST /api/feed/community
     * Create an applicant-authored career update, job-search ask, interview note,
     * salary intel, referral offer, or founder update.
     */
    @PostMapping("/community")
    @PreAuthorize("hasAnyAuthority('APPLICANT', 'ADMIN')")
    public Mono<ResponseEntity<FeedPostResponse>> createCommunityPost(
            @RequestBody CreateFeedPostRequest request,
            @RequestHeader("Authorization") String authHeader) {

        Long userId = jwtTokenProvider.getUserIdFromToken(extractToken(authHeader));
        return feedService.createCommunityPost(userId, request)
                .map(post -> ResponseEntity.status(HttpStatus.CREATED).body(post));
    }

    /**
     * POST /api/feed/{postId}/react
     * Toggle a reaction on a post. Requires auth.
     */
    @PostMapping("/{postId}/react")
    @PreAuthorize("isAuthenticated()")
    public Mono<ResponseEntity<FeedPostResponse>> reactToPost(
            @PathVariable Long postId,
            @RequestBody FeedReactionRequest request,
            @RequestHeader("Authorization") String authHeader) {

        Long userId = jwtTokenProvider.getUserIdFromToken(extractToken(authHeader));
        return feedService.reactToPost(postId, userId, request)
                .map(ResponseEntity::ok);
    }

    /**
     * POST /api/companies/{orgId}/follow
     * Toggle follow/unfollow. Returns { following: true/false }.
     */
    @PostMapping("/companies/{orgId}/follow")
    @PreAuthorize("isAuthenticated()")
    public Mono<ResponseEntity<Map<String, Boolean>>> toggleFollow(
            @PathVariable Long orgId,
            @RequestHeader("Authorization") String authHeader) {

        Long userId = jwtTokenProvider.getUserIdFromToken(extractToken(authHeader));
        return feedService.toggleFollow(userId, orgId)
                .map(following -> ResponseEntity.ok(Map.of("following", following)));
    }

    // --- Helpers ---

    private Long resolveUserId(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            try {
                return jwtTokenProvider.getUserIdFromToken(authHeader.substring(7));
            } catch (Exception ignored) {}
        }
        return null;
    }

    private String extractToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        throw new BadRequestException("Invalid authorization header");
    }
}
