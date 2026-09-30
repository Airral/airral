package com.airral.service;

import com.airral.domain.Interview;
import com.airral.domain.Organization;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InterviewCalendarTest {

    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    private static final Organization ACME = Organization.builder().name("Acme").timezone("Europe/London").build();

    private static Interview at(LocalDateTime when, String zone) {
        return Interview.builder().id(900L).interviewDate(when).durationMinutes(60).timeZone(zone).build();
    }

    private static String event(Interview interview, Organization company, String summary, String description) {
        return InterviewCalendar.event(interview, company, summary, description, "notifications@airral.com",
                List.of(new InterviewCalendar.Attendee("Amy \"A\" Adams", "amy@example.com")), NOW).orElseThrow();
    }

    @Test
    @DisplayName("times are the booker's wall clock, converted to UTC, across daylight saving")
    void utcTimes() {
        String summer = event(at(LocalDateTime.of(2026, 10, 1, 14, 0), "America/New_York"), ACME, "Interview", null);
        String winter = event(at(LocalDateTime.of(2026, 11, 2, 9, 30), "America/New_York"), ACME, "Interview", null);

        assertThat(summer).contains("DTSTART:20261001T180000Z").contains("DTEND:20261001T190000Z")
                .contains("DTSTAMP:20260928T120000Z").contains("UID:interview-900@airral.com").contains("METHOD:PUBLISH");
        assertThat(winter).contains("DTSTART:20261102T143000Z");
    }

    @Test
    @DisplayName("an interview without a zone uses the company's, and without either there is no file")
    void zoneFallback() {
        assertThat(event(at(LocalDateTime.of(2026, 10, 1, 14, 0), null), ACME, "Interview", null))
                .contains("DTSTART:20261001T130000Z");
        assertThat(event(at(LocalDateTime.of(2026, 10, 1, 14, 0), "Mars/Olympus"), ACME, "Interview", null))
                .contains("DTSTART:20261001T130000Z");
        assertThat(InterviewCalendar.event(at(LocalDateTime.of(2026, 10, 1, 14, 0), null),
                Organization.builder().name("Acme").build(), "Interview", null, "n@airral.com", List.of(), NOW))
                .isEmpty();
    }

    @Test
    @DisplayName("typed text is escaped, so it cannot add properties or break a line")
    void escaping() {
        String ics = event(at(LocalDateTime.of(2026, 10, 1, 14, 0), "UTC"), ACME,
                "Interview; with, Acme\\Co", "Line one\r\nATTENDEE:mailto:evil@example.com");

        assertThat(ics).contains("SUMMARY:Interview\\; with\\, Acme\\\\Co")
                .contains("DESCRIPTION:Line one\\nATTENDEE:mailto:evil@example.com")
                .contains("ATTENDEE;CN=\"Amy  A  Adams\";ROLE=REQ-PARTICIPANT:mailto:amy@example.com");
        // The injected text stays inside DESCRIPTION: exactly one attendee line.
        assertThat(Arrays.stream(ics.split("\r\n")).filter(line -> line.startsWith("ATTENDEE"))).hasSize(1);
    }

    @Test
    @DisplayName("long lines fold at 75 octets without splitting a character, and unfold back")
    void folding() {
        String description = "Résumé notes: " + "ünïcödé ".repeat(30);
        String ics = event(at(LocalDateTime.of(2026, 10, 1, 14, 0), "UTC"), ACME, "Interview", description);

        for (String line : ics.split("\r\n")) {
            assertThat(line.getBytes(StandardCharsets.UTF_8).length).as(line).isLessThanOrEqualTo(75);
        }
        String unfolded = ics.replace("\r\n ", "");
        assertThat(unfolded).contains("DESCRIPTION:" + description);
    }
}
