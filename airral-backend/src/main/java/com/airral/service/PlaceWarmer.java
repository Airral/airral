package com.airral.service;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Keeps the first page of the biggest places ready, so a visitor's first search
 * for Denver or Texas is answered from memory rather than from a database that
 * may be busy with the sync.
 *
 * <p>One place at a time with a pause between, so the warm-up is never what loads
 * the database. A place that fails is skipped; the next round tries it again.
 * Rounds repeat inside the cache's lifetime so the pages never expire unwarmed.
 */
@Component
@Profile("!sync & !reparse-resumes")
public class PlaceWarmer {

    private static final Logger log = LoggerFactory.getLogger(PlaceWarmer.class);
    static final int STATES = 15;
    static final int CITIES = 35;
    static final Duration FIRST_DELAY = Duration.ofSeconds(90);
    static final Duration EVERY = Duration.ofMinutes(8);
    static final Duration PAUSE = Duration.ofMillis(400);
    static final int PAGE_SIZE = 50;

    private final LocationIndex locations;
    private final CandidateJobSearchService search;
    private Disposable running;

    @Autowired
    public PlaceWarmer(LocationIndex locations, CandidateJobSearchService search) {
        this.locations = locations;
        this.search = search;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        running = Flux.interval(FIRST_DELAY, EVERY, Schedulers.boundedElastic())
                .onBackpressureDrop()
                .concatMap(tick -> round(), 1)
                .subscribe(ok -> { }, error -> log.warn("Place warm-up stopped: {}", error.toString()));
    }

    @jakarta.annotation.PreDestroy
    void stop() {
        if (running != null) {
            running.dispose();
        }
    }

    Mono<Void> round() {
        return locations.topPlaces(STATES, CITIES)
                .flatMapMany(Flux::fromIterable)
                .concatMap(place -> search.warmPublicPlacePage(place, PAGE_SIZE)
                        .onErrorResume(error -> {
                            log.debug("Could not warm {}: {}", place, error.toString());
                            return Mono.empty();
                        })
                        .then(Mono.delay(PAUSE).then()), 1)
                .then()
                .onErrorResume(error -> {
                    log.warn("Place warm-up round failed: {}", error.toString());
                    return Mono.empty();
                });
    }
}
