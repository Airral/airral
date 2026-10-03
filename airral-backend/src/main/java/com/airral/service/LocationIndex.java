package com.airral.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

/**
 * The places jobs are in, for the Where field's suggestions.
 *
 * <p>Drawn from the postings themselves, so every suggestion has jobs behind it.
 * Locations are written a dozen ways ("Denver, CO", "Denver, Colorado, United
 * States", "US, CO, Denver", "Denver, CO; Austin, TX"), so each is reduced to a
 * label that {@link LocationFilter} reads back as the same place: "Denver, CO",
 * or a state's name. Remote postings have no place and are left out.
 *
 * <p>Built from one grouped query over ~7,500 distinct locations and kept for
 * 30 minutes; a stale list is served while a fresh one is built, and a failed
 * build keeps the old list.
 */
@Component
public class LocationIndex {

    private static final Logger log = LoggerFactory.getLogger(LocationIndex.class);
    static final Duration TTL = Duration.ofMinutes(30);
    static final int MAX_LIMIT = 15;

    private static final Pattern COUNTRY = Pattern.compile("^(united states( of america)?|usa|u\\.?s\\.?a?\\.?)$");

    private final ExternalJobPostingStore store;
    private final Clock clock;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();
    private final AtomicReference<Mono<Snapshot>> building = new AtomicReference<>();

    @Autowired
    public LocationIndex(ExternalJobPostingStore store) {
        this(store, Clock.systemUTC());
    }

    LocationIndex(ExternalJobPostingStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public record Suggestion(String label, String kind, long jobs) {
    }

    record Snapshot(List<Suggestion> states, List<Suggestion> cities, Instant builtAt) {
    }

    /** Suggestions for what has been typed so far; with nothing typed, the biggest places. */
    public Mono<List<Suggestion>> suggest(String typed, int limit) {
        int max = Math.max(1, Math.min(limit, MAX_LIMIT));
        return snapshot().map(snap -> match(snap, typed, max));
    }

    /** The biggest places, states first, for the page warmer. */
    public Mono<List<String>> topPlaces(int states, int cities) {
        return snapshot().map(snap -> {
            List<String> labels = new ArrayList<>();
            snap.states().stream().limit(states).forEach(state -> labels.add(state.label()));
            snap.cities().stream().limit(cities).forEach(city -> labels.add(city.label()));
            return labels;
        });
    }

    private Mono<Snapshot> snapshot() {
        Snapshot current = snapshot.get();
        if (current != null) {
            if (Duration.between(current.builtAt(), clock.instant()).compareTo(TTL) > 0) {
                refresh().subscribe(ok -> { }, error -> { });
            }
            return Mono.just(current);
        }
        return refresh();
    }

    private Mono<Snapshot> refresh() {
        Mono<Snapshot> fresh = Mono.defer(() -> store.findActiveLocationCounts().collectList()
                        .map(rows -> build(rows, clock.instant()))
                        .doOnNext(snapshot::set)
                        .doOnError(error -> log.warn("Location suggestions could not be built: {}", error.toString()))
                        .doFinally(signal -> building.set(null)))
                .cache();
        Mono<Snapshot> existing = building.updateAndGet(running -> running != null ? running : fresh);
        return existing;
    }

    static Snapshot build(List<ExternalJobPostingStore.LocationCount> rows, Instant now) {
        Map<String, Long> cityJobs = new LinkedHashMap<>();
        for (ExternalJobPostingStore.LocationCount row : rows) {
            for (String label : cityLabels(row.location())) {
                cityJobs.merge(label, row.jobs(), Long::sum);
            }
        }
        List<Suggestion> cities = cityJobs.entrySet().stream()
                .map(entry -> new Suggestion(entry.getKey(), "city", entry.getValue()))
                .sorted(Comparator.comparingLong(Suggestion::jobs).reversed().thenComparing(Suggestion::label))
                .toList();

        List<Suggestion> states = new ArrayList<>();
        for (String abbreviation : LocationFilter.abbreviations()) {
            LocationFilter filter = LocationFilter.parse(abbreviation).orElseThrow();
            long jobs = rows.stream()
                    .filter(row -> filter.matches(row.location()))
                    .mapToLong(ExternalJobPostingStore.LocationCount::jobs)
                    .sum();
            if (jobs > 0) {
                states.add(new Suggestion(titleCase(LocationFilter.nameOf(abbreviation)), "state", jobs));
            }
        }
        states.sort(Comparator.comparingLong(Suggestion::jobs).reversed().thenComparing(Suggestion::label));
        return new Snapshot(List.copyOf(states), cities, now);
    }

    /**
     * "Denver, CO" for each place in a location, or none for remote and
     * unparseable ones. A city is only worth suggesting with its state.
     */
    static List<String> cityLabels(String location) {
        List<String> labels = new ArrayList<>();
        if (location == null) {
            return labels;
        }
        for (String place : location.split(";")) {
            List<String> raw = new ArrayList<>();
            for (String part : place.split(",")) {
                if (!part.trim().isEmpty()) {
                    raw.add(part.trim());
                }
            }
            // "US, CA, Santa Clara" leads with the country, then the state, then the city.
            if (raw.size() >= 3 && raw.get(0).equalsIgnoreCase("us")) {
                raw = new ArrayList<>(List.of(raw.get(2), raw.get(1)));
            }
            List<String> parts = new ArrayList<>();
            for (String part : raw) {
                if (!COUNTRY.matcher(part.toLowerCase(Locale.US)).matches()) {
                    parts.add(part);
                }
            }
            if (parts.size() < 2 || parts.get(0).toLowerCase(Locale.US).contains("remote")) {
                continue;
            }
            // "USA - Denver, CO" and "LPS - Denver, CO" lead with a program or country, not the city.
            String city = parts.get(0);
            if (city.contains(" - ")) {
                city = city.substring(city.lastIndexOf(" - ") + 3).trim();
            }
            String state = LocationFilter.abbreviationOf(parts.get(1));
            if (state == null || city.length() > 40 || !city.matches("[\\p{L}][\\p{L} .'\\-]*")
                    || LocationFilter.abbreviationOf(city) != null) {
                continue;
            }
            String label = city + ", " + state;
            if (!labels.contains(label)) {
                labels.add(label);
            }
        }
        return labels;
    }

    static List<Suggestion> match(Snapshot snap, String typed, int limit) {
        String q = typed == null ? "" : typed.toLowerCase(Locale.US).replace(".", " ").replaceAll("\\s+", " ").trim();
        if (q.isEmpty()) {
            List<Suggestion> top = new ArrayList<>(snap.cities().subList(0, Math.min(limit, snap.cities().size())));
            return top;
        }
        List<Suggestion> out = new ArrayList<>();
        for (Suggestion state : snap.states()) {
            String name = state.label().toLowerCase(Locale.US);
            String abbreviation = LocationFilter.abbreviationOf(name).toLowerCase(Locale.US);
            if (name.startsWith(q) || abbreviation.equals(q)) {
                out.add(state);
            }
        }
        int stateCount = Math.min(out.size(), 3);
        out = new ArrayList<>(out.subList(0, stateCount));
        for (Suggestion city : snap.cities()) {
            if (out.size() >= limit) {
                break;
            }
            String label = city.label().toLowerCase(Locale.US);
            if (label.startsWith(q) || label.contains(" " + q)) {
                out.add(city);
            }
        }
        return out;
    }

    private static String titleCase(String value) {
        StringBuilder out = new StringBuilder();
        for (String word : value.split(" ")) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(word.equals("of") ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1));
        }
        return out.toString();
    }
}
