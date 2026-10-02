package com.airral.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a typed place means, and the match it becomes.
 *
 * <p>Place searches worked only by accident: the box is a text search over the
 * whole posting. "co" matched "San Francisco, Company", "texas" matched a Texas
 * mentioned in a description, and "colorado" missed every job written
 * "Denver, CO". Each case below is one of those, or an input shape people use.
 */
class LocationFilterTest {

    private static LocationFilter parse(String input) {
        return LocationFilter.parse(input).orElseThrow();
    }

    private static void assertPlace(String input, String city, String state, String text) {
        LocationFilter filter = parse(input);
        assertThat(filter.city()).as("city of \"%s\"", input).isEqualTo(city);
        assertThat(filter.state()).as("state of \"%s\"", input).isEqualTo(state);
        assertThat(filter.terms() == null ? null : String.join("|", filter.terms()))
                .as("terms of \"%s\"", input).isEqualTo(text);
    }

    @Test
    @DisplayName("a bare city is a city")
    void city() {
        assertPlace("Denver", "denver", null, null);
        assertPlace("  san   francisco ", "san francisco", null, null);
        assertPlace("Kansas City", "kansas city", null, null);
        assertPlace("Virginia Beach", "virginia beach", null, null);
        assertPlace("Oregon City", "oregon city", null, null);
    }

    @Test
    @DisplayName("a bare state, by name or abbreviation, is a state")
    void state() {
        assertPlace("Colorado", null, "CO", null);
        assertPlace("co", null, "CO", null);
        assertPlace("TX", null, "TX", null);
        assertPlace("New York", null, "NY", null);
        assertPlace("West Virginia", null, "WV", null);
        assertPlace("Washington", null, "WA", null);
        assertPlace("D.C.", null, "DC", null);
        assertPlace("Washington DC", null, "DC", null);
    }

    @Test
    @DisplayName("a city and its state, however it is written")
    void cityAndState() {
        assertPlace("Denver, CO", "denver", "CO", null);
        assertPlace("denver co", "denver", "CO", null);
        assertPlace("Denver, Colorado", "denver", "CO", null);
        assertPlace("denver colorado", "denver", "CO", null);
        assertPlace("Denver, CO, United States", "denver", "CO", null);
        assertPlace("Denver, CO, USA", "denver", "CO", null);
        assertPlace("Boston MA", "boston", "MA", null);
        assertPlace("New York, NY", "new york", "NY", null);
        assertPlace("New York, New York", "new york", "NY", null);
        assertPlace("Charleston, West Virginia", "charleston", "WV", null);
        assertPlace("charleston west virginia", "charleston", "WV", null);
        assertPlace("Kansas City, MO", "kansas city", "MO", null);
        assertPlace("Washington, DC", "washington", "DC", null);
    }

    @Test
    @DisplayName("a place that is neither is matched as written")
    void freeText() {
        assertPlace("Park Meadows Mall", "park meadows mall", null, null);
        // Postings write "London, United Kingdom" with a comma, so each part is matched on its own.
        assertPlace("Denver, Narnia", null, null, "denver|narnia");
        assertPlace("London, United Kingdom", null, null, "london|united kingdom");
    }

    @Test
    @DisplayName("matches() applies the same rule as the SQL")
    void inMemoryMatch() {
        assertThat(parse("denver").matches("Denver, CO, United States")).isTrue();
        assertThat(parse("Colorado").matches("Denver, CO")).isTrue();
        assertThat(parse("Colorado").matches("Denver, Colorado")).isTrue();
        assertThat(parse("Colorado").matches("San Francisco, Company")).isFalse();
        assertThat(parse("co").matches("Costa Mesa, California")).isFalse();
        assertThat(parse("LA").matches("La Jolla")).isFalse();
        assertThat(parse("LA").matches("Baton Rouge, LA")).isTrue();
        assertThat(parse("denver, co").matches("Denver, CO; New York, NY")).isTrue();
        assertThat(parse("denver, co").matches("Denver, KS")).isFalse();
        assertThat(parse("london, united kingdom").matches("London, United Kingdom")).isTrue();
        assertThat(parse("denver").matches(null)).isFalse();
    }

    @Test
    @DisplayName("blank input is no filter")
    void blank() {
        assertThat(LocationFilter.parse(null)).isEmpty();
        assertThat(LocationFilter.parse("   ")).isEmpty();
        assertThat(LocationFilter.parse(" , ")).isEmpty();
        assertThat(LocationFilter.parse("United States")).isEmpty();
    }

    @Test
    @DisplayName("an abbreviation matches only as a word of its own, in capitals")
    void abbreviationIsAWholeWord() {
        Pattern co = Pattern.compile(LocationFilter.abbreviationRegex("CO"));
        assertThat(co.matcher("Denver, CO").find()).isTrue();
        assertThat(co.matcher("Denver, CO, United States").find()).isTrue();
        assertThat(co.matcher("Hanover CO").find()).as("written without a comma").isTrue();
        assertThat(co.matcher("US - CO").find()).isTrue();
        // The cases that made "co" a bad search.
        assertThat(co.matcher("San Francisco, Company").find()).isFalse();
        assertThat(co.matcher("Denver, Colorado").find()).isFalse();
        assertThat(co.matcher("Costa Mesa, California").find()).isFalse();

        // "LA" the state, not the "La" in a place name.
        Pattern la = Pattern.compile(LocationFilter.abbreviationRegex("LA"));
        assertThat(la.matcher("Baton Rouge, LA").find()).isTrue();
        assertThat(la.matcher("La Jolla").find()).isFalse();
        assertThat(la.matcher("Denver, CO; La Vista, NE").find()).isFalse();
        assertThat(la.matcher("La Mesa, CA, United States").find()).isFalse();

        Pattern dc = Pattern.compile(LocationFilter.abbreviationRegex("DC"));
        assertThat(dc.matcher("Washington, DC").find()).isTrue();
        assertThat(dc.matcher("Washington, D.C.").find()).isTrue();
        assertThat(dc.matcher("Washington DC").find()).isTrue();
        assertThat(dc.matcher("DCIM, California").find()).isFalse();
    }

    @Test
    @DisplayName("the SQL ANDs the parts, binds every value, and escapes wildcards")
    void sql() {
        LocationFilter.Sql both = parse("Denver, CO").toSql("LOWER(p.location)", "p.location", "loc");
        assertThat(both.condition())
                .contains("LOWER(p.location) LIKE :locCity")
                .contains("LOWER(p.location) LIKE :locStateName")
                .contains("p.location ~ :locStateAbbr");
        assertThat(both.binds())
                .containsEntry("locCity", "%denver%")
                .containsEntry("locStateName", "%colorado%");

        assertThat(parse("Colorado").toSql("c", "r", "x").binds()).doesNotContainKey("xCity");
        assertThat(parse("100%_off").toSql("c", "r", "x").binds().get("xCity")).isEqualTo("%100\\%\\_off%");
        // Nothing the person typed is in the SQL text itself.
        assertThat(parse("Denver'; DROP TABLE users; --").toSql("c", "r", "x").condition()).doesNotContain("DROP");
    }
}
