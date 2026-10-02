package com.airral.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import reactor.core.scheduler.Schedulers;

/**
 * The order of the public job feed with no one employer filling it, kept in
 * memory.
 *
 * <p>Ordered newest day first, the feed's front page belonged to whichever
 * employer lists the most jobs: 49 of the first 50 were Target's, and the first
 * two cards were the same job twice. {@link ExternalJobPostingStore#findDiversifiedFeedIds}
 * orders it properly, but reads the whole active table -- ~17k blocks, several
 * seconds on production's disk -- so it cannot run per visitor.
 *
 * <p>So it runs in the background, and what is kept is only the ordered ids
 * (5,000 longs): a page is then {@code WHERE id = ANY(...)} for 51 ids.
 *
 * <ul>
 *   <li><b>Never slower than before.</b> With no list ready, {@link #idsOrNull}
 *       answers null and the caller serves the ordinary feed; a build starts.</li>
 *   <li><b>Stale beats waiting.</b> An expired list is still returned while a
 *       fresh one is built.</li>
 *   <li><b>One build at a time</b>, for the whole process, so this can never be
 *       the thing that loads the database. A failed build is not retried for a
 *       minute.</li>
 *   <li><b>Bounded.</b> At most {@value #MAX_KEYS} filter combinations are kept;
 *       the rest get the ordinary feed.</li>
 * </ul>
 */
@Component
// Not in the batch runs: the sync and the resume re-parse serve no visitors, and the
// warm-up below would read the whole job table for nothing.
@Profile("!sync & !reparse-resumes")
public class DiverseFeedIndex {

    private static final Logger log = LoggerFactory.getLogger(DiverseFeedIndex.class);

    /** Postings one employer gets in each round of the feed. */
    static final int PER_ROUND = 3;
    /** 100 pages of 50. A list this long may be a truncation; see {@link Feed#complete()}. */
    static final int MAX_IDS = 5_000;
    static final int MAX_KEYS = 8;
    static final Duration TTL = Duration.ofMinutes(30);
    static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(1);

    /** The feed for one set of filters. */
    record Key(String source, String boardToken, int maxAgeDays, String workMode,
               boolean salaryPosted, String experienceLevel, boolean visaFriendly) {
    }

    /** A built list. {@code complete} is false when it hit {@link #MAX_IDS} and more exist. */
    public record Feed(List<Long> ids, boolean complete) {
    }

    private record Entry(Feed feed, Instant builtAt) {
    }

    private final ExternalJobPostingStore store;
    private final Clock clock;
    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();
    private final Map<Key, Instant> failedAt = new ConcurrentHashMap<>();
    private final AtomicBoolean building = new AtomicBoolean(false);

    @Autowired
    public DiverseFeedIndex(ExternalJobPostingStore store) {
        this(store, Clock.systemUTC());
    }

    DiverseFeedIndex(ExternalJobPostingStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * The built list for these filters, or null when there is none yet (a build
     * is started) or this combination is not being kept.
     */
    public Feed idsOrNull(String source, String boardToken, int maxAgeDays, ExplicitJobFilters filters) {
        Key key = keyFor(source, boardToken, maxAgeDays, filters);
        Entry entry = entries.get(key);
        Instant now = clock.instant();

        if (entry == null) {
            if (entries.size() < MAX_KEYS) {
                startBuild(key, filters);
            }
            return null;
        }
        if (now.isAfter(entry.builtAt().plus(TTL))) {
            startBuild(key, filters);
        }
        return entry.feed();
    }

    /** Builds the default feed (everyone's first page) as soon as the app is ready to serve. */
    @EventListener(ApplicationReadyEvent.class)
    void warmDefaultFeed() {
        Key key = keyFor("all", null, 60, ExplicitJobFilters.none());
        startBuild(key, ExplicitJobFilters.none());
    }

    private void startBuild(Key key, ExplicitJobFilters filters) {
        Instant failed = failedAt.get(key);
        if (failed != null && clock.instant().isBefore(failed.plus(RETRY_AFTER_FAILURE))) {
            return;
        }
        if (!building.compareAndSet(false, true)) {
            return;
        }
        long startedAt = System.nanoTime();
        store.findDiversifiedFeedIds(key.source(), key.boardToken(), key.maxAgeDays(), filters, PER_ROUND, MAX_IDS)
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(signal -> building.set(false))
                .subscribe(
                        ids -> {
                            entries.put(key, new Entry(new Feed(List.copyOf(ids), ids.size() < MAX_IDS), clock.instant()));
                            failedAt.remove(key);
                            log.info("Built the diversified job feed ({} ids, {} ms)",
                                    ids.size(), (System.nanoTime() - startedAt) / 1_000_000);
                        },
                        error -> {
                            failedAt.put(key, clock.instant());
                            log.warn("Could not build the diversified job feed: {}", error.getMessage());
                        });
    }

    static Key keyFor(String source, String boardToken, int maxAgeDays, ExplicitJobFilters filters) {
        ExplicitJobFilters f = filters == null ? ExplicitJobFilters.none() : filters;
        return new Key(
                source == null ? "all" : source.trim().toLowerCase(java.util.Locale.US),
                boardToken == null ? "" : boardToken.trim(),
                maxAgeDays,
                f.hasWorkMode() ? f.normalizedWorkMode() : "",
                f.wantsPostedSalary(),
                f.hasExperienceLevel() ? f.normalizedExperienceLevel() : "",
                f.wantsVisaFriendly());
    }
}
