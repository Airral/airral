package com.airral.service;

import com.airral.domain.Application;
import com.airral.domain.Interview;
import com.airral.domain.InterviewKit;
import com.airral.domain.InterviewScorecard;
import com.airral.domain.Job;
import com.airral.dto.interview.KitCriterion;
import com.airral.dto.interview.ScoreRating;
import com.airral.dto.request.ScorecardRequest;
import com.airral.dto.response.InterviewResponse;
import com.airral.dto.response.ScorecardResponse;
import com.airral.exception.BadRequestException;
import com.airral.exception.ConflictException;
import com.airral.exception.NotFoundException;
import com.airral.repository.ApplicationRepository;
import com.airral.repository.InterviewKitRepository;
import com.airral.repository.InterviewRepository;
import com.airral.repository.InterviewScorecardRepository;
import com.airral.repository.JobRepository;
import com.airral.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Interviewers' scorecards.
 *
 * <p>Only an interviewer on the interview writes its scorecard, and only
 * their own. It is a draft until they submit it, and then it is fixed and the
 * company's HR managers and the job's hiring manager can read it. Drafts stay
 * with the interviewer, so nobody reads a half-formed opinion, and an
 * interviewer does not see anyone else's scorecard before writing their own.
 */
@Service
public class ScorecardService {

    private final InterviewRepository interviewRepository;
    private final InterviewScorecardRepository scorecardRepository;
    private final ApplicationRepository applicationRepository;
    private final JobRepository jobRepository;
    private final InterviewKitRepository kitRepository;
    private final InterviewKitService kits;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public ScorecardService(InterviewRepository interviewRepository,
                            InterviewScorecardRepository scorecardRepository,
                            ApplicationRepository applicationRepository,
                            JobRepository jobRepository,
                            InterviewKitRepository kitRepository,
                            InterviewKitService kits,
                            UserRepository userRepository,
                            ObjectMapper objectMapper) {
        this.interviewRepository = interviewRepository;
        this.scorecardRepository = scorecardRepository;
        this.applicationRepository = applicationRepository;
        this.jobRepository = jobRepository;
        this.kitRepository = kitRepository;
        this.kits = kits;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    /** What an interview is about: its application, job and the job's kit. */
    private record Context(Interview interview, Application application, Job job, Optional<InterviewKit> kit) {}

    /** The caller's scorecard for an interview they are on: saved, or a blank one to fill in. */
    public Mono<ScorecardResponse> myScorecard(Long interviewId, Long userId, Long organizationId) {
        return asInterviewer(interviewId, userId, organizationId)
                .flatMap(context -> scorecardRepository.findByInterviewIdAndInterviewerId(interviewId, userId)
                        .map(Optional::of)
                        .defaultIfEmpty(Optional.empty())
                        .flatMap(saved -> withName(userId, name -> saved
                                .map(card -> toResponse(card, context, name))
                                .orElseGet(() -> blank(context, userId, name)))));
    }

    /** Save the caller's scorecard as a draft, or submit it. */
    public Mono<ScorecardResponse> saveMyScorecard(Long interviewId, Long userId, Long organizationId,
                                                   ScorecardRequest request) {
        return asInterviewer(interviewId, userId, organizationId)
                .flatMap(context -> scorecardRepository.findByInterviewIdAndInterviewerId(interviewId, userId)
                        .map(Optional::of)
                        .defaultIfEmpty(Optional.empty())
                        .flatMap(existing -> {
                            if (existing.isPresent() && InterviewScorecard.SUBMITTED.equals(existing.get().getStatus())) {
                                return Mono.error(new ConflictException("You already submitted this scorecard"));
                            }
                            List<ScoreRating> ratings = rate(criteriaFor(context), request.getRatings());
                            boolean submit = Boolean.TRUE.equals(request.getSubmit());
                            if (submit && (request.getRecommendation() == null
                                    || ratings.stream().anyMatch(rating -> rating.getRating() == null))) {
                                return Mono.error(new BadRequestException(
                                        "Rate every criterion and choose a recommendation before you submit"));
                            }
                            LocalDateTime now = LocalDateTime.now();
                            InterviewScorecard card = existing.orElseGet(() -> InterviewScorecard.builder()
                                    .interviewId(interviewId)
                                    .interviewerId(userId)
                                    .createdAt(now)
                                    .build());
                            card.setRatings(write(ratings));
                            card.setOverallNotes(trimmed(request.getOverallNotes()));
                            card.setRecommendation(request.getRecommendation());
                            card.setStatus(submit ? InterviewScorecard.SUBMITTED : InterviewScorecard.DRAFT);
                            card.setSubmittedAt(submit ? now : null);
                            card.setUpdatedAt(now);
                            return scorecardRepository.save(card)
                                    .flatMap(saved -> withName(userId, name -> toResponse(saved, context, name)));
                        }));
    }

    /**
     * The submitted scorecards for an application, for the company reviewing
     * it: HR, or the job's hiring manager. Drafts are not included.
     */
    public Flux<ScorecardResponse> submittedForApplication(Long applicationId, Long organizationId, JobScope scope) {
        return applicationRepository.findByIdAndOrganizationId(applicationId, organizationId)
                .filter(application -> scope.allows(application.getJobId()))
                .switchIfEmpty(Mono.error(new NotFoundException("Application not found")))
                .flatMapMany(application -> jobRepository.findById(application.getJobId())
                        .flatMapMany(job -> kitOf(job).flatMapMany(kit -> interviewRepository
                                .findByApplicationId(applicationId)
                                .concatMap(interview -> {
                                    Context context = new Context(interview, application, job, kit);
                                    return scorecardRepository.findSubmittedByInterviewId(interview.getId())
                                            .concatMap(card -> withName(card.getInterviewerId(),
                                                    name -> toResponse(card, context, name)));
                                }))));
    }

    /** Each interview with the viewer's own scorecard status: DRAFT, SUBMITTED, or none yet. */
    public Flux<InterviewResponse> withMyScorecardStatus(Flux<InterviewResponse> interviews, Long userId) {
        return interviews.concatMap(interview -> scorecardRepository
                .findByInterviewIdAndInterviewerId(interview.getId(), userId)
                .map(card -> {
                    interview.setMyScorecardStatus(card.getStatus());
                    return interview;
                })
                .defaultIfEmpty(interview));
    }

    /**
     * The application behind an interview the caller is on, when its resume is
     * attached: interviewers read the resume of the person they interview.
     */
    public Mono<Application> applicationWithResumeForInterviewer(Long interviewId, Long userId, Long organizationId) {
        return asInterviewer(interviewId, userId, organizationId)
                .map(Context::application)
                .filter(application -> application.getResumeDocumentId() != null && application.getApplicantId() != null)
                .switchIfEmpty(Mono.error(new NotFoundException("No resume is attached to this application")));
    }

    /** An interview in the caller's company that they are on. Anything else is "not found". */
    private Mono<Context> asInterviewer(Long interviewId, Long userId, Long organizationId) {
        return interviewRepository.findByIdAndOrganizationId(interviewId, organizationId)
                .filterWhen(interview -> interviewRepository.isInterviewer(interview.getId(), userId))
                .switchIfEmpty(Mono.error(new NotFoundException("Interview not found")))
                .flatMap(interview -> applicationRepository.findById(interview.getApplicationId())
                        .flatMap(application -> jobRepository.findById(application.getJobId())
                                .flatMap(job -> kitOf(job)
                                        .map(kit -> new Context(interview, application, job, kit)))))
                .switchIfEmpty(Mono.error(new NotFoundException("Interview not found")));
    }

    private Mono<Optional<InterviewKit>> kitOf(Job job) {
        if (job.getInterviewKitId() == null) return Mono.just(Optional.empty());
        return kitRepository.findByIdAndOrganizationId(job.getInterviewKitId(), job.getOrganizationId())
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty());
    }

    private List<KitCriterion> criteriaFor(Context context) {
        return kits.criteriaOf(context.kit().orElse(null));
    }

    /** The criteria, each with the rating and notes the request gives it. Criteria the kit does not have are ignored. */
    static List<ScoreRating> rate(List<KitCriterion> criteria, List<ScorecardRequest.Rating> given) {
        Map<String, ScorecardRequest.Rating> byCriterion = new HashMap<>();
        if (given != null) {
            for (ScorecardRequest.Rating rating : given) {
                if (rating != null && rating.getCriterion() != null) {
                    byCriterion.putIfAbsent(rating.getCriterion().trim().toLowerCase(Locale.ROOT), rating);
                }
            }
        }
        return criteria.stream().map(criterion -> {
            ScorecardRequest.Rating rating = byCriterion.get(criterion.getName().toLowerCase(Locale.ROOT));
            return new ScoreRating(criterion.getName(), criterion.getCategory(), criterion.getWeight(),
                    rating == null ? null : rating.getRating(),
                    rating == null ? null : trimmed(rating.getNotes()));
        }).toList();
    }

    /** The weighted average of the ratings given, to one decimal, or null before any. */
    static Double weightedScore(List<ScoreRating> ratings) {
        double total = 0;
        int weights = 0;
        for (ScoreRating rating : ratings) {
            if (rating.getRating() == null) continue;
            int weight = rating.getWeight() == null ? 1 : rating.getWeight();
            total += rating.getRating() * weight;
            weights += weight;
        }
        return weights == 0 ? null : Math.round(total / weights * 10) / 10.0;
    }

    private ScorecardResponse blank(Context context, Long userId, String interviewerName) {
        List<ScoreRating> ratings = rate(criteriaFor(context), null);
        return base(context, interviewerName)
                .interviewerId(userId)
                .ratings(ratings)
                .status(InterviewScorecard.DRAFT)
                .build();
    }

    private ScorecardResponse toResponse(InterviewScorecard card, Context context, String interviewerName) {
        List<ScoreRating> saved = read(card.getRatings());
        // A draft is rated against the kit as it is now; a submitted scorecard keeps what it was rated on.
        List<ScoreRating> ratings = InterviewScorecard.SUBMITTED.equals(card.getStatus())
                ? saved
                : rate(criteriaFor(context), saved.stream()
                        .map(rating -> new ScorecardRequest.Rating(rating.getCriterion(), rating.getRating(), rating.getNotes()))
                        .toList());
        return base(context, interviewerName)
                .id(card.getId())
                .interviewerId(card.getInterviewerId())
                .ratings(ratings)
                .overallNotes(card.getOverallNotes())
                .recommendation(card.getRecommendation())
                .status(card.getStatus())
                .submittedAt(card.getSubmittedAt())
                .weightedScore(weightedScore(ratings))
                .build();
    }

    private ScorecardResponse.ScorecardResponseBuilder base(Context context, String interviewerName) {
        InterviewKit kit = context.kit().orElse(null);
        return ScorecardResponse.builder()
                .interviewId(context.interview().getId())
                .applicationId(context.application().getId())
                .interviewerName(interviewerName)
                .candidateName(context.application().getApplicantName())
                .jobTitle(context.job().getTitle())
                .interviewDate(context.interview().getInterviewDate())
                .durationMinutes(context.interview().getDurationMinutes())
                .timeZone(context.interview().getTimeZone())
                .kitName(kit == null ? null : kit.getName())
                .questions(kits.questionsOf(kit));
    }

    private <T> Mono<T> withName(Long userId, Function<String, T> build) {
        return userRepository.findById(userId)
                .map(InterviewService::displayName)
                .defaultIfEmpty("A former teammate")
                .map(build);
    }

    private static String trimmed(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    private Json write(List<ScoreRating> ratings) {
        try {
            return Json.of(objectMapper.writeValueAsString(ratings));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not store a scorecard", e);
        }
    }

    private List<ScoreRating> read(Json json) {
        if (json == null) return List.of();
        try {
            List<ScoreRating> ratings = objectMapper.readValue(json.asString(), new TypeReference<>() {});
            return ratings == null ? List.of() : ratings;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read a scorecard", e);
        }
    }
}
