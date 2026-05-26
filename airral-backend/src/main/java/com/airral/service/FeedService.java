package com.airral.service;

import com.airral.domain.CompanyFollow;
import com.airral.domain.FeedPost;
import com.airral.domain.FeedReaction;
import com.airral.domain.User;
import com.airral.dto.request.CreateFeedPostRequest;
import com.airral.dto.request.FeedReactionRequest;
import com.airral.dto.response.FeedPageResponse;
import com.airral.dto.response.FeedPostResponse;
import com.airral.exception.BadRequestException;
import com.airral.exception.NotFoundException;
import com.airral.repository.*;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class FeedService {
    private static final int MAX_POST_CONTENT_LENGTH = 2_000;
    private static final int MAX_SHORT_FIELD_LENGTH = 255;
    private static final int COMMUNITY_POST_LIMIT = 5;
    private static final int COMMUNITY_POST_WINDOW_MINUTES = 10;
    private static final Pattern URL_PATTERN = Pattern.compile("(?i)(https?://|www\\.)");
    private static final Set<String> ALLOWED_REACTIONS = Set.of("USEFUL", "INSPIRING", "PRACTICAL");

    private final FeedPostRepository feedPostRepository;
    private final FeedReactionRepository feedReactionRepository;
    private final FeedCommentRepository feedCommentRepository;
    private final CompanyFollowRepository companyFollowRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;

    public FeedService(
            FeedPostRepository feedPostRepository,
            FeedReactionRepository feedReactionRepository,
            FeedCommentRepository feedCommentRepository,
            CompanyFollowRepository companyFollowRepository,
            OrganizationRepository organizationRepository,
            UserRepository userRepository) {
        this.feedPostRepository = feedPostRepository;
        this.feedReactionRepository = feedReactionRepository;
        this.feedCommentRepository = feedCommentRepository;
        this.companyFollowRepository = companyFollowRepository;
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
    }

    /**
     * Paginated public feed — works for unauthenticated users too.
     * When userId is provided, viewerReaction is populated per post.
     */
    public Mono<FeedPageResponse> getPublicFeed(int page, int size, Long userId) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(1, Math.min(size, 50));
        long offset = (long) (safePage - 1) * safeSize;

        Flux<FeedPost> posts = userId == null
                ? feedPostRepository.findPublicFeedPaged(safeSize, offset)
                : feedPostRepository.findMemberFeedPaged(safeSize, offset);
        Mono<Long> total = userId == null
                ? feedPostRepository.countPublicFeed()
                : feedPostRepository.countMemberFeed();

        return posts
                .flatMap(post -> toResponse(post, userId))
                .collectList()
                .zipWith(total)
                .map(tuple -> {
                    List<FeedPostResponse> items = tuple.getT1();
                    long totalItems = tuple.getT2();
                    int totalPages = (int) Math.ceil((double) totalItems / safeSize);
                    return FeedPageResponse.builder()
                            .items(items)
                            .page(safePage)
                            .pageSize(safeSize)
                            .totalItems(totalItems)
                            .totalPages(totalPages)
                            .hasNext(safePage < totalPages)
                            .build();
                });
    }

    /**
     * Create a new feed post for an organization.
     */
    public Mono<FeedPostResponse> createPost(Long organizationId, Long userId, CreateFeedPostRequest request) {
        validatePostRequest(request);
        LocalDateTime now = LocalDateTime.now();

        FeedPost post = FeedPost.builder()
                .organizationId(organizationId)
                .authorId(userId)
                .authorType("COMPANY")
                .authorDisplayName(null)
                .postType(request.getPostType() != null ? request.getPostType() : "COMPANY_SIGNAL")
                .visibility(normalizeVisibility(request.getVisibility(), "PUBLIC"))
                .topic(trimToNull(request.getTopic()))
                .content(request.getContent().trim())
                .linkedJobId(request.getLinkedJobId())
                .linkedExternalJobKey(request.getLinkedExternalJobKey())
                .targetType(trimToNull(request.getTargetType()))
                .targetLabel(trimToNull(request.getTargetLabel()))
                .moderationStatus(moderationStatusFor(request.getContent()))
                .reportCount(0)
                .publishedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();

        return feedPostRepository.save(post)
                .flatMap(saved -> toResponse(saved, userId));
    }

    /**
     * Create a candidate-authored career/community post.
     */
    public Mono<FeedPostResponse> createCommunityPost(Long userId, CreateFeedPostRequest request) {
        validatePostRequest(request);
        LocalDateTime windowStart = LocalDateTime.now().minusMinutes(COMMUNITY_POST_WINDOW_MINUTES);

        return feedPostRepository.countRecentByAuthor(userId, "APPLICANT", windowStart)
                .flatMap(recentPosts -> {
                    if (recentPosts >= COMMUNITY_POST_LIMIT) {
                        return Mono.error(new BadRequestException("You are posting too quickly. Please wait a few minutes and try again."));
                    }
                    return userRepository.findById(userId)
                            .switchIfEmpty(Mono.error(new NotFoundException("User not found")));
                })
                .flatMap(user -> {
                    LocalDateTime now = LocalDateTime.now();
                    FeedPost post = FeedPost.builder()
                            .organizationId(null)
                            .authorId(userId)
                            .authorType("APPLICANT")
                            .authorDisplayName(displayName(user))
                            .postType(normalizeCommunityPostType(request.getPostType()))
                            .visibility(normalizeVisibility(request.getVisibility(), "AUTHENTICATED"))
                            .topic(trimToNull(request.getTopic()))
                            .content(request.getContent().trim())
                            .linkedJobId(request.getLinkedJobId())
                            .linkedExternalJobKey(request.getLinkedExternalJobKey())
                            .targetType(trimToNull(request.getTargetType()))
                            .targetLabel(trimToNull(request.getTargetLabel()))
                            .moderationStatus(moderationStatusFor(request.getContent()))
                            .reportCount(0)
                            .publishedAt(now)
                            .createdAt(now)
                            .updatedAt(now)
                            .build();

                    return feedPostRepository.save(post);
                })
                .flatMap(saved -> toResponse(saved, userId));
    }

    /**
     * Toggle reaction on a post. If user already has the same reaction, removes it.
     * If user has a different reaction, replaces it.
     */
    public Mono<FeedPostResponse> reactToPost(Long postId, Long userId, FeedReactionRequest request) {
        String reactionType = normalizeReactionType(request == null ? null : request.getReactionType());

        return feedPostRepository.findById(postId)
                .switchIfEmpty(Mono.error(new NotFoundException("Feed post not found")))
                .flatMap(post ->
                        feedReactionRepository.findByPostIdAndUserId(postId, userId)
                                .flatMap(existing -> {
                                    if (existing.getReactionType().equals(reactionType)) {
                                        // Same reaction — remove it (toggle off)
                                        return feedReactionRepository.delete(existing).thenReturn(post);
                                    } else {
                                        // Different reaction — update
                                        existing.setReactionType(reactionType);
                                        return feedReactionRepository.save(existing).thenReturn(post);
                                    }
                                })
                                .switchIfEmpty(
                                        // No existing reaction — add one
                                        feedReactionRepository.save(FeedReaction.builder()
                                                .postId(postId)
                                                .userId(userId)
                                                .reactionType(reactionType)
                                                .createdAt(LocalDateTime.now())
                                                .build()
                                        ).thenReturn(post)
                                )
                )
                .flatMap(post -> toResponse(post, userId));
    }

    /**
     * Toggle follow/unfollow for a company. Returns true if now following.
     */
    public Mono<Boolean> toggleFollow(Long userId, Long organizationId) {
        return companyFollowRepository.existsByUserIdAndOrganizationId(userId, organizationId)
                .flatMap(exists -> {
                    if (exists) {
                        return companyFollowRepository.deleteByUserIdAndOrganizationId(userId, organizationId)
                                .thenReturn(false);
                    } else {
                        return companyFollowRepository.save(CompanyFollow.builder()
                                .userId(userId)
                                .organizationId(organizationId)
                                .createdAt(LocalDateTime.now())
                                .build()
                        ).thenReturn(true);
                    }
                });
    }

    // --- Helpers ---

    private Mono<FeedPostResponse> toResponse(FeedPost post, Long viewerUserId) {
        Mono<String> companyName = post.getOrganizationId() == null
                ? Mono.just("")
                : organizationRepository.findById(post.getOrganizationId())
                        .map(org -> org.getName())
                        .defaultIfEmpty("Unknown Company");

        Mono<Long> usefulCount     = feedReactionRepository.countByPostIdAndReactionType(post.getId(), "USEFUL").defaultIfEmpty(0L);
        Mono<Long> inspiringCount  = feedReactionRepository.countByPostIdAndReactionType(post.getId(), "INSPIRING").defaultIfEmpty(0L);
        Mono<Long> practicalCount  = feedReactionRepository.countByPostIdAndReactionType(post.getId(), "PRACTICAL").defaultIfEmpty(0L);
        Mono<Long> commentCount    = feedCommentRepository.countByPostId(post.getId()).defaultIfEmpty(0L);

        Mono<String> viewerReaction = viewerUserId != null
                ? feedReactionRepository.findByPostIdAndUserId(post.getId(), viewerUserId)
                        .map(FeedReaction::getReactionType)
                        .defaultIfEmpty("")
                : Mono.just("");

        return Mono.zip(companyName, usefulCount, inspiringCount, practicalCount, commentCount, viewerReaction)
                .map(tuple -> FeedPostResponse.builder()
                        .id(post.getId())
                        .organizationId(post.getOrganizationId())
                        .companyName(tuple.getT1())
                        .authorType(post.getAuthorType())
                        .authorId(null)
                        .authorDisplayName(resolveAuthorDisplayName(post, tuple.getT1()))
                        .postType(post.getPostType())
                        .visibility(post.getVisibility())
                        .topic(post.getTopic())
                        .content(post.getContent())
                        .linkedJobId(post.getLinkedJobId())
                        .linkedExternalJobKey(post.getLinkedExternalJobKey())
                        .targetType(post.getTargetType())
                        .targetLabel(post.getTargetLabel())
                        .usefulCount(tuple.getT2())
                        .inspiringCount(tuple.getT3())
                        .practicalCount(tuple.getT4())
                        .commentCount(tuple.getT5())
                        .viewerReaction(tuple.getT6().isBlank() ? null : tuple.getT6())
                        .publishedAt(post.getPublishedAt())
                        .createdAt(post.getCreatedAt())
                        .build());
    }

    private String normalizeCommunityPostType(String postType) {
        if (postType == null || postType.isBlank()) {
            return "JOB_SEARCH_ASK";
        }

        return switch (postType.trim().toUpperCase()) {
            case "CAREER_UPDATE", "JOB_SEARCH_ASK", "INTERVIEW_NOTE", "SALARY_INTEL", "REFERRAL_OFFER", "FOUNDER_UPDATE", "COMMUNITY_TIP" ->
                    postType.trim().toUpperCase();
            default -> "JOB_SEARCH_ASK";
        };
    }

    private void validatePostRequest(CreateFeedPostRequest request) {
        if (request == null) {
            throw new BadRequestException("Post content is required");
        }
        if (request.getContent() == null || request.getContent().isBlank()) {
            throw new BadRequestException("Post content is required");
        }
        if (request.getContent().trim().length() > MAX_POST_CONTENT_LENGTH) {
            throw new BadRequestException("Post content must be 2000 characters or less");
        }
        validateShortField("Topic", request.getTopic());
        validateShortField("Target type", request.getTargetType());
        validateShortField("Target label", request.getTargetLabel());
        validateShortField("External job key", request.getLinkedExternalJobKey());
    }

    private void validateShortField(String fieldName, String value) {
        if (value != null && value.length() > MAX_SHORT_FIELD_LENGTH) {
            throw new BadRequestException(fieldName + " must be " + MAX_SHORT_FIELD_LENGTH + " characters or less");
        }
    }

    private String normalizeVisibility(String visibility, String fallback) {
        if (visibility == null || visibility.isBlank()) {
            return fallback;
        }

        return switch (visibility.trim().toUpperCase()) {
            case "PUBLIC", "AUTHENTICATED", "APPLICANTS_ONLY" -> visibility.trim().toUpperCase();
            default -> throw new BadRequestException("Unsupported feed visibility");
        };
    }

    private String normalizeReactionType(String reactionType) {
        if (reactionType == null || reactionType.isBlank()) {
            throw new BadRequestException("Reaction type is required");
        }

        String normalized = reactionType.trim().toUpperCase();
        if (!ALLOWED_REACTIONS.contains(normalized)) {
            throw new BadRequestException("Unsupported reaction type");
        }
        return normalized;
    }

    private String moderationStatusFor(String content) {
        int urlCount = 0;
        var matcher = URL_PATTERN.matcher(content == null ? "" : content);
        while (matcher.find()) {
            urlCount++;
        }
        return urlCount > 2 ? "PENDING" : "APPROVED";
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String displayName(User user) {
        String fullName = user.getFullName();
        if (fullName != null && !fullName.isBlank()) {
            return fullName.trim();
        }
        return "AIRRAL member";
    }

    private String resolveAuthorDisplayName(FeedPost post, String companyName) {
        if (post.getAuthorDisplayName() != null && !post.getAuthorDisplayName().isBlank()) {
            return post.getAuthorDisplayName();
        }
        if ("COMPANY".equalsIgnoreCase(post.getAuthorType()) && companyName != null && !companyName.isBlank()) {
            return companyName;
        }
        return "AIRRAL member";
    }
}
