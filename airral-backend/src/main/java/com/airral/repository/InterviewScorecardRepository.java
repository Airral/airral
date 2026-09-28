package com.airral.repository;

import com.airral.domain.InterviewScorecard;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface InterviewScorecardRepository extends R2dbcRepository<InterviewScorecard, Long> {

    @Query("SELECT * FROM interview_scorecards WHERE interview_id = :interviewId AND interviewer_id = :interviewerId")
    Mono<InterviewScorecard> findByInterviewIdAndInterviewerId(Long interviewId, Long interviewerId);

    @Query("SELECT * FROM interview_scorecards WHERE interview_id = :interviewId AND status = 'SUBMITTED' " +
           "ORDER BY submitted_at")
    Flux<InterviewScorecard> findSubmittedByInterviewId(Long interviewId);
}
