package com.airral.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.airral.service.ExternalJobPostingStore.LocationCount;

class LocationIndexTest {

    private static LocationIndex.Snapshot snapshot() {
        return LocationIndex.build(List.of(
                new LocationCount("Denver, CO", 100),
                new LocationCount("Denver, Colorado, United States", 50),
                new LocationCount("US, CO, Denver", 5),
                new LocationCount("Denver, CO; Austin, TX", 10),
                new LocationCount("Austin, TX", 80),
                new LocationCount("Remote - US", 900),
                new LocationCount("Costa Mesa, California, United States", 40),
                new LocationCount("Colorado Springs, CO", 30),
                new LocationCount("Washington, DC", 20),
                new LocationCount("London, United Kingdom", 60)), Instant.EPOCH);
    }

    @Test
    @DisplayName("every spelling of a place becomes one label that the filter reads back as that place")
    void labels() {
        assertThat(LocationIndex.cityLabels("Denver, Colorado, United States")).containsExactly("Denver, CO");
        assertThat(LocationIndex.cityLabels("US, CO, Denver")).containsExactly("Denver, CO");
        assertThat(LocationIndex.cityLabels("Denver, CO; Austin, TX")).containsExactly("Denver, CO", "Austin, TX");
        assertThat(LocationIndex.cityLabels("USA - Denver, CO")).containsExactly("Denver, CO");
        assertThat(LocationIndex.cityLabels("Winston-Salem, NC")).containsExactly("Winston-Salem, NC");
        assertThat(LocationIndex.cityLabels("Remote - US")).isEmpty();
        assertThat(LocationIndex.cityLabels("Colorado")).isEmpty();
        assertThat(LocationIndex.cityLabels(null)).isEmpty();
    }

    @Test
    @DisplayName("counts add up across spellings, and remote postings are no place")
    void counts() {
        LocationIndex.Snapshot snap = snapshot();
        assertThat(snap.cities().get(0)).isEqualTo(new LocationIndex.Suggestion("Denver, CO", "city", 165));
        assertThat(snap.cities()).noneMatch(city -> city.label().toLowerCase().contains("remote"));
        assertThat(snap.states()).extracting(LocationIndex.Suggestion::label).contains("Colorado", "Texas");
    }

    @Test
    @DisplayName("typing offers matching states first, then cities, biggest first")
    void matching() {
        LocationIndex.Snapshot snap = snapshot();
        assertThat(LocationIndex.match(snap, "col", 8)).extracting(LocationIndex.Suggestion::label)
                .containsExactly("Colorado", "Colorado Springs, CO");
        assertThat(LocationIndex.match(snap, "den", 8)).extracting(LocationIndex.Suggestion::label)
                .containsExactly("Denver, CO");
        assertThat(LocationIndex.match(snap, "tx", 8)).extracting(LocationIndex.Suggestion::label)
                .contains("Texas");
        assertThat(LocationIndex.match(snap, "springs", 8)).extracting(LocationIndex.Suggestion::label)
                .containsExactly("Colorado Springs, CO");
        assertThat(LocationIndex.match(snap, "", 2)).hasSize(2);
        assertThat(LocationIndex.match(snap, "zzz", 8)).isEmpty();
    }

    @Test
    @DisplayName("a suggestion is a search that finds jobs: its label parses to a place that matches its own source")
    void roundTrip() {
        assertThat(LocationFilter.parse("Denver, CO").orElseThrow().matches("Denver, Colorado, United States")).isTrue();
        assertThat(LocationFilter.parse("Colorado").orElseThrow().matches("Denver, CO")).isTrue();
    }
}
