package com.airral.service;

import com.airral.domain.Application;
import com.airral.domain.Interview;
import com.airral.domain.InterviewKit;
import com.airral.domain.InterviewScorecard;
import com.airral.domain.Job;
import com.airral.domain.User;
import com.airral.dto.interview.ScoreRating;
import com.airral.dto.request.ScorecardRequest;
import com.airral.exception.BadRequestException;
import com.airral.exception.ConflictException;
import com.airral.exception.NotFoundException;
import com.airral.repository.ApplicationRepository;
import com.airral.repository.InterviewKitRepository;
import com.airral.repository.InterviewRepository;
import com.airral.repository.InterviewScorecardRepository;
import com.airral.repository.JobRepository;
import com.airral.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who writes a scorecard, what it rates, and who reads it.
 */
class ScorecardServiceTest {

    private static final long ACME = 1L;
    private static final long IVAN = 4L;    // on the interview
    private static final long NINA = 9L;    // at Acme, not on it
    private static final long INTERVIEW = 900L;

    private final InterviewRepository interviews = mock(InterviewRepository.class);
    private final InterviewScorecardRepository scorecards = mock(InterviewScorecardRepository.class);
    private final ApplicationRepository applications = mock(ApplicationRepository.class);
    private final JobRepository jobs = mock(JobRepository.class);
    private final InterviewKitRepository kitRepository = mock(InterviewKitRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScorecardService service = new ScorecardService(interviews, scorecards, applications, jobs,
            kitRepository, new InterviewKitService(kitRepository, objectMapper), users, objectMapper);

    private final Job job = Job.builder().id(10L).organizationId(ACME).title("Store manager").interviewKitId(50L).build();

    @BeforeEach
    void setUp() {
        Interview interview = Interview.builder().id(INTERVIEW).applicationId(100L)
                .interviewDate(LocalDateTime.of(2026, 10, 1, 14, 0)).durationMinutes(45)
                .timeZone("America/Chicago").status("SCHEDULED").build();
        Application application = Application.builder().id(100L).jobId(10L).applicantName("Amy Adams").build();
        when(interviews.findByIdAndOrganizationId(INTERVIEW, ACME)).thenReturn(Mono.just(interview));
        when(interviews.isInterviewer(INTERVIEW, IVAN)).thenReturn(Mono.just(true));
        when(interviews.isInterviewer(INTERVIEW, NINA)).thenReturn(Mono.just(false));
        when(interviews.findByApplicationId(100L)).thenReturn(Flux.just(interview));
        when(applications.findById(100L)).thenReturn(Mono.just(application));
        when(applications.findByIdAndOrganizationId(100L, ACME)).thenReturn(Mono.just(application));
        when(jobs.findById(10L)).thenReturn(Mono.just(job));
        when(kitRepository.findByIdAndOrganizationId(50L, ACME)).thenReturn(Mono.just(InterviewKit.builder()
                .id(50L).organizationId(ACME).name("Store manager loop")
                .questions(Json.of("[{\"text\":\"Tell us about a busy Saturday\",\"category\":\"Operations\"}]"))
                .criteria(Json.of("[{\"name\":\"Leading a team\",\"category\":\"People\",\"weight\":3},"
                        + "{\"name\":\"Stock and ordering\",\"category\":\"Operations\",\"weight\":1}]"))
                .build()));
        when(users.findById(IVAN)).thenReturn(Mono.just(User.builder().id(IVAN).firstName("Ivan").lastName("Ito").build()));
        when(scorecards.findByInterviewIdAndInterviewerId(INTERVIEW, IVAN)).thenReturn(Mono.empty());
        when(scorecards.save(any(InterviewScorecard.class))).thenAnswer(inv -> {
            InterviewScorecard card = inv.getArgument(0);
            if (card.getId() == null) card.setId(70L);
            return Mono.just(card);
        });
    }

    private static ScorecardRequest ratings(Integer team, Integer stock, String recommendation, boolean submit) {
        return ScorecardRequest.builder()
                .ratings(List.of(new ScorecardRequest.Rating("leading a team", team, " Calm under pressure "),
                        new ScorecardRequest.Rating("Stock and ordering", stock, null),
                        new ScorecardRequest.Rating("Something the kit does not rate", 1, "ignored")))
                .overallNotes("Would hire")
                .recommendation(recommendation)
                .submit(submit)
                .build();
    }

    @Test
    @DisplayName("an interviewer starts from a blank scorecard with the job's kit")
    void blankScorecard() {
        StepVerifier.create(service.myScorecard(INTERVIEW, IVAN, ACME))
                .assertNext(card -> {
                    assertThat(card.getId()).isNull();
                    assertThat(card.getStatus()).isEqualTo("DRAFT");
                    assertThat(card.getKitName()).isEqualTo("Store manager loop");
                    assertThat(card.getQuestions()).extracting("text").containsExactly("Tell us about a busy Saturday");
                    assertThat(card.getRatings()).extracting(ScoreRating::getCriterion)
                            .containsExactly("Leading a team", "Stock and ordering");
                    assertThat(card.getCandidateName()).isEqualTo("Amy Adams");
                    assertThat(card.getTimeZone()).isEqualTo("America/Chicago");
                    assertThat(card.getInterviewerName()).isEqualTo("Ivan Ito");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("a job without a kit is rated on the standard criteria")
    void noKit() {
        job.setInterviewKitId(null);

        StepVerifier.create(service.myScorecard(INTERVIEW, IVAN, ACME))
                .assertNext(card -> {
                    assertThat(card.getKitName()).isNull();
                    assertThat(card.getQuestions()).isEmpty();
                    assertThat(card.getRatings()).hasSize(InterviewKitService.STANDARD_CRITERIA.size());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("only an interviewer on the interview has a scorecard for it")
    void notOnTheInterview() {
        StepVerifier.create(service.myScorecard(INTERVIEW, NINA, ACME))
                .expectError(NotFoundException.class).verify();
        StepVerifier.create(service.saveMyScorecard(INTERVIEW, NINA, ACME, ratings(4, 4, "HIRE", true)))
                .expectError(NotFoundException.class).verify();
        verify(scorecards, never()).save(any());
    }

    @Test
    @DisplayName("a draft keeps the kit's criteria only, and scores what is rated so far")
    void saveDraft() {
        StepVerifier.create(service.saveMyScorecard(INTERVIEW, IVAN, ACME, ratings(5, null, null, false)))
                .assertNext(card -> {
                    assertThat(card.getStatus()).isEqualTo("DRAFT");
                    assertThat(card.getRatings()).extracting(ScoreRating::getCriterion)
                            .containsExactly("Leading a team", "Stock and ordering");
                    assertThat(card.getRatings().get(0).getNotes()).isEqualTo("Calm under pressure");
                    assertThat(card.getWeightedScore()).isEqualTo(5.0);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("submitting needs every criterion rated and a recommendation")
    void submitNeedsEverything() {
        StepVerifier.create(service.saveMyScorecard(INTERVIEW, IVAN, ACME, ratings(5, null, "HIRE", true)))
                .expectError(BadRequestException.class).verify();
        StepVerifier.create(service.saveMyScorecard(INTERVIEW, IVAN, ACME, ratings(5, 3, null, true)))
                .expectError(BadRequestException.class).verify();
        verify(scorecards, never()).save(any());

        StepVerifier.create(service.saveMyScorecard(INTERVIEW, IVAN, ACME, ratings(5, 3, "HIRE", true)))
                .assertNext(card -> {
                    assertThat(card.getStatus()).isEqualTo("SUBMITTED");
                    assertThat(card.getSubmittedAt()).isNotNull();
                    // (5 x 3 + 3 x 1) / 4
                    assertThat(card.getWeightedScore()).isEqualTo(4.5);
                })
                .verifyComplete();
        ArgumentCaptor<InterviewScorecard> saved = ArgumentCaptor.forClass(InterviewScorecard.class);
        verify(scorecards).save(saved.capture());
        assertThat(saved.getValue().getInterviewerId()).isEqualTo(IVAN);
    }

    @Test
    @DisplayName("a submitted scorecard cannot change, and keeps the criteria it was rated on")
    void submittedIsFixed() {
        InterviewScorecard submitted = InterviewScorecard.builder().id(70L).interviewId(INTERVIEW).interviewerId(IVAN)
                .status("SUBMITTED").recommendation("HIRE").submittedAt(LocalDateTime.now())
                .ratings(Json.of("[{\"criterion\":\"An old criterion\",\"weight\":2,\"rating\":4}]")).build();
        when(scorecards.findByInterviewIdAndInterviewerId(INTERVIEW, IVAN)).thenReturn(Mono.just(submitted));

        StepVerifier.create(service.saveMyScorecard(INTERVIEW, IVAN, ACME, ratings(1, 1, "NO_HIRE", true)))
                .expectError(ConflictException.class).verify();
        StepVerifier.create(service.myScorecard(INTERVIEW, IVAN, ACME))
                .assertNext(card -> assertThat(card.getRatings()).extracting(ScoreRating::getCriterion)
                        .containsExactly("An old criterion"))
                .verifyComplete();
        verify(scorecards, never()).save(any());
    }

    @Test
    @DisplayName("HR reads the submitted scorecards; a hiring manager only on their own jobs")
    void teamReadsSubmitted() {
        InterviewScorecard submitted = InterviewScorecard.builder().id(70L).interviewId(INTERVIEW).interviewerId(IVAN)
                .status("SUBMITTED").recommendation("HIRE")
                .ratings(Json.of("[{\"criterion\":\"Leading a team\",\"weight\":3,\"rating\":4}]")).build();
        when(scorecards.findSubmittedByInterviewId(INTERVIEW)).thenReturn(Flux.just(submitted));

        StepVerifier.create(service.submittedForApplication(100L, ACME, JobScope.wholeCompany()))
                .assertNext(card -> {
                    assertThat(card.getInterviewerName()).isEqualTo("Ivan Ito");
                    assertThat(card.getRecommendation()).isEqualTo("HIRE");
                    assertThat(card.getWeightedScore()).isEqualTo(4.0);
                })
                .verifyComplete();
        StepVerifier.create(service.submittedForApplication(100L, ACME, JobScope.only(Set.of(99L))))
                .expectError(NotFoundException.class).verify();
    }
}
