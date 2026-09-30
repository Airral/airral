package com.airral.service;

import com.airral.domain.InterviewKit;
import com.airral.dto.interview.KitCriterion;
import com.airral.dto.interview.KitQuestion;
import com.airral.dto.request.InterviewKitRequest;
import com.airral.exception.ConflictException;
import com.airral.exception.NotFoundException;
import com.airral.repository.InterviewKitRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterviewKitServiceTest {

    private static final long ACME = 1L;

    private final InterviewKitRepository repository = mock(InterviewKitRepository.class);
    private final InterviewKitService kits = new InterviewKitService(repository, new ObjectMapper());

    @BeforeEach
    void setUp() {
        when(repository.save(any(InterviewKit.class))).thenAnswer(inv -> {
            InterviewKit kit = inv.getArgument(0);
            if (kit.getId() == null) kit.setId(50L);
            return Mono.just(kit);
        });
        when(repository.nameTaken(eq(ACME), anyString(), anyLong())).thenReturn(Mono.just(false));
    }

    @Test
    @DisplayName("a kit keeps what was filled in: blank rows go, criteria get a weight, and a name counts once")
    void createCleansTheLists() {
        InterviewKitRequest request = InterviewKitRequest.builder()
                .name("  Store manager loop ")
                .questions(List.of(new KitQuestion(" Tell us about a busy Saturday ", "Operations"),
                        new KitQuestion("  ", "Blank")))
                .criteria(List.of(new KitCriterion("Leading a team", "People", null),
                        new KitCriterion("leading a TEAM", "People", 3),
                        new KitCriterion("Stock and ordering", null, 1),
                        new KitCriterion(" ", null, 2)))
                .build();

        StepVerifier.create(kits.create(ACME, request))
                .assertNext(kit -> {
                    assertThat(kit.getName()).isEqualTo("Store manager loop");
                    assertThat(kit.getDurationMinutes()).isEqualTo(60);
                    assertThat(kit.getQuestions()).containsExactly(new KitQuestion("Tell us about a busy Saturday", "Operations"));
                    assertThat(kit.getCriteria()).containsExactly(
                            new KitCriterion("Leading a team", "People", 2),
                            new KitCriterion("Stock and ordering", null, 1));
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("two kits in a company cannot share a name")
    void namesAreUnique() {
        when(repository.nameTaken(ACME, "Store manager loop", -1L)).thenReturn(Mono.just(true));

        StepVerifier.create(kits.create(ACME, InterviewKitRequest.builder().name("Store manager loop").build()))
                .expectError(ConflictException.class)
                .verify();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a company cannot change another company's kit")
    void otherCompanysKit() {
        when(repository.findByIdAndOrganizationId(77L, ACME)).thenReturn(Mono.empty());

        StepVerifier.create(kits.update(77L, ACME, InterviewKitRequest.builder().name("Mine now").build()))
                .expectError(NotFoundException.class)
                .verify();
        StepVerifier.create(kits.delete(77L, ACME))
                .expectError(NotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("a job with no kit, or a kit with no criteria, is rated on the standard criteria")
    void standardCriteria() {
        InterviewKit questionsOnly = InterviewKit.builder().criteria(Json.of("[]")).questions(Json.of("[]")).build();

        assertThat(kits.criteriaOf(null)).isEqualTo(InterviewKitService.STANDARD_CRITERIA);
        assertThat(kits.criteriaOf(questionsOnly)).isEqualTo(InterviewKitService.STANDARD_CRITERIA);
    }
}
