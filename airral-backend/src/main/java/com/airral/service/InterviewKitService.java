package com.airral.service;

import com.airral.domain.InterviewKit;
import com.airral.dto.interview.KitCriterion;
import com.airral.dto.interview.KitQuestion;
import com.airral.dto.request.InterviewKitRequest;
import com.airral.dto.response.InterviewKitResponse;
import com.airral.exception.ConflictException;
import com.airral.exception.NotFoundException;
import com.airral.repository.InterviewKitRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A company's interview kits: the questions to ask in an interview and the
 * criteria interviewers rate a candidate on.
 */
@Service
public class InterviewKitService {

    /** What interviewers rate when a job has no kit. Written for any kind of job, not only tech. */
    public static final List<KitCriterion> STANDARD_CRITERIA = List.of(
            new KitCriterion("Skills for the role", "Skills", 3),
            new KitCriterion("Problem solving", "Skills", 3),
            new KitCriterion("Communication", "Working style", 2),
            new KitCriterion("Working with others", "Working style", 2),
            new KitCriterion("Relevant experience", "Experience", 2),
            new KitCriterion("Motivation for this role", "Experience", 1));

    static final int DEFAULT_WEIGHT = 2;

    private final InterviewKitRepository kitRepository;
    private final ObjectMapper objectMapper;

    public InterviewKitService(InterviewKitRepository kitRepository, ObjectMapper objectMapper) {
        this.kitRepository = kitRepository;
        this.objectMapper = objectMapper;
    }

    public Flux<InterviewKitResponse> list(Long organizationId) {
        return kitRepository.findByOrganizationId(organizationId).map(this::toResponse);
    }

    public Mono<InterviewKitResponse> create(Long organizationId, InterviewKitRequest request) {
        String name = request.getName().trim();
        return kitRepository.nameTaken(organizationId, name, -1L)
                .flatMap(taken -> taken
                        ? Mono.<InterviewKit>error(new ConflictException("You already have a kit called " + name))
                        : kitRepository.save(fill(InterviewKit.builder()
                                .organizationId(organizationId)
                                .createdAt(LocalDateTime.now())
                                .build(), request)))
                .map(this::toResponse);
    }

    public Mono<InterviewKitResponse> update(Long id, Long organizationId, InterviewKitRequest request) {
        String name = request.getName().trim();
        return kitRepository.findByIdAndOrganizationId(id, organizationId)
                .switchIfEmpty(Mono.error(new NotFoundException("Interview kit not found")))
                .flatMap(kit -> kitRepository.nameTaken(organizationId, name, kit.getId())
                        .flatMap(taken -> taken
                                ? Mono.<InterviewKit>error(new ConflictException("You already have a kit called " + name))
                                : kitRepository.save(fill(kit, request))))
                .map(this::toResponse);
    }

    /** Remove a kit. Jobs that used it keep going with the standard criteria. */
    public Mono<Void> delete(Long id, Long organizationId) {
        return kitRepository.findByIdAndOrganizationId(id, organizationId)
                .switchIfEmpty(Mono.error(new NotFoundException("Interview kit not found")))
                .flatMap(kitRepository::delete);
    }

    /** The criteria a kit rates, or the standard ones when it has none. */
    public List<KitCriterion> criteriaOf(InterviewKit kit) {
        List<KitCriterion> criteria = kit == null ? List.of() : read(kit.getCriteria(), new TypeReference<>() {});
        return criteria.isEmpty() ? STANDARD_CRITERIA : criteria;
    }

    public List<KitQuestion> questionsOf(InterviewKit kit) {
        return kit == null ? List.of() : read(kit.getQuestions(), new TypeReference<>() {});
    }

    private InterviewKit fill(InterviewKit kit, InterviewKitRequest request) {
        List<KitQuestion> questions = request.getQuestions() == null ? List.of() : request.getQuestions().stream()
                .filter(Objects::nonNull)
                .filter(question -> question.getText() != null && !question.getText().isBlank())
                .map(question -> new KitQuestion(question.getText().trim(), trimmed(question.getCategory())))
                .toList();
        List<KitCriterion> criteria = request.getCriteria() == null ? List.of() : request.getCriteria().stream()
                .filter(Objects::nonNull)
                .filter(criterion -> criterion.getName() != null && !criterion.getName().isBlank())
                .map(criterion -> new KitCriterion(criterion.getName().trim(), trimmed(criterion.getCategory()),
                        criterion.getWeight() != null ? criterion.getWeight() : DEFAULT_WEIGHT))
                // One rating per criterion: a second one with the same name would be ambiguous.
                .filter(distinctBy())
                .toList();
        kit.setName(request.getName().trim());
        kit.setDescription(trimmed(request.getDescription()));
        kit.setDurationMinutes(request.getDurationMinutes() != null ? request.getDurationMinutes() : 60);
        kit.setQuestions(write(questions));
        kit.setCriteria(write(criteria));
        kit.setUpdatedAt(LocalDateTime.now());
        return kit;
    }

    private static Predicate<KitCriterion> distinctBy() {
        Set<String> seen = new HashSet<>();
        return criterion -> seen.add(criterion.getName().toLowerCase(Locale.ROOT));
    }

    InterviewKitResponse toResponse(InterviewKit kit) {
        return InterviewKitResponse.builder()
                .id(kit.getId())
                .name(kit.getName())
                .description(kit.getDescription())
                .durationMinutes(kit.getDurationMinutes())
                .questions(questionsOf(kit))
                .criteria(read(kit.getCriteria(), new TypeReference<List<KitCriterion>>() {}))
                .updatedAt(kit.getUpdatedAt())
                .build();
    }

    private static String trimmed(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    private Json write(Object value) {
        try {
            return Json.of(objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not store an interview kit", e);
        }
    }

    private <T> List<T> read(Json json, TypeReference<List<T>> type) {
        if (json == null) return List.of();
        try {
            List<T> values = objectMapper.readValue(json.asString(), type);
            return values == null ? List.of() : values;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read an interview kit", e);
        }
    }
}
