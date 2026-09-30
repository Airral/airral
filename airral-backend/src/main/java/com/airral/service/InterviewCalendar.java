package com.airral.service;

import com.airral.domain.Interview;
import com.airral.domain.Organization;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Calendar files (RFC 5545) for interviews, attached to the emails that
 * announce them.
 *
 * <p>Times are written in UTC, so every calendar places the interview at the
 * same moment. That needs the zone the interview was booked in: the booker's,
 * or else the company's. Without either there is no file, because a guessed
 * zone puts the interview at the wrong hour in someone's calendar.
 */
public final class InterviewCalendar {

    /** The file name the attachment gets. */
    public static final String FILE_NAME = "interview.ics";

    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private InterviewCalendar() {
    }

    /** Someone on the event. */
    public record Attendee(String name, String email) {}

    /** When the interview starts, if the zone it was booked in is known. */
    public static Optional<Instant> startOf(Interview interview, Organization company) {
        if (interview.getInterviewDate() == null) return Optional.empty();
        return zoneOf(interview, company).map(zone -> interview.getInterviewDate().atZone(zone).toInstant());
    }

    /** The interview's own zone, else the company's; empty when neither is a zone Java knows. */
    public static Optional<ZoneId> zoneOf(Interview interview, Organization company) {
        for (String candidate : new String[] {interview.getTimeZone(), company == null ? null : company.getTimezone()}) {
            if (candidate == null || candidate.isBlank()) continue;
            try {
                return Optional.of(ZoneId.of(candidate.trim()));
            } catch (DateTimeException ignored) {
                // A zone a company typed that Java does not know: try the next one.
            }
        }
        return Optional.empty();
    }

    /**
     * An event for the interview, or empty when its time cannot be placed.
     * PUBLISH rather than REQUEST: the file adds the interview to a calendar,
     * and replies to AIRRAL's sending address would reach nobody.
     */
    public static Optional<String> event(Interview interview, Organization company, String summary,
                                         String description, String organizerEmail, List<Attendee> attendees,
                                         Instant now) {
        return startOf(interview, company).map(start -> {
            int minutes = interview.getDurationMinutes() != null ? interview.getDurationMinutes() : 60;
            List<String> lines = new ArrayList<>();
            lines.add("BEGIN:VCALENDAR");
            lines.add("PRODID:-//AIRRAL//Interviews//EN");
            lines.add("VERSION:2.0");
            lines.add("CALSCALE:GREGORIAN");
            lines.add("METHOD:PUBLISH");
            lines.add("BEGIN:VEVENT");
            lines.add("UID:interview-" + interview.getId() + "@airral.com");
            lines.add("DTSTAMP:" + UTC.format(now));
            lines.add("DTSTART:" + UTC.format(start));
            lines.add("DTEND:" + UTC.format(start.plusSeconds(minutes * 60L)));
            lines.add("SUMMARY:" + text(summary));
            if (description != null && !description.isBlank()) {
                lines.add("DESCRIPTION:" + text(description));
            }
            lines.add("ORGANIZER;CN=AIRRAL:mailto:" + address(organizerEmail));
            for (Attendee attendee : attendees) {
                if (attendee.email() == null || attendee.email().isBlank()) continue;
                lines.add("ATTENDEE;CN=" + parameter(attendee.name()) + ";ROLE=REQ-PARTICIPANT:mailto:"
                        + address(attendee.email()));
            }
            lines.add("STATUS:CONFIRMED");
            lines.add("SEQUENCE:0");
            lines.add("END:VEVENT");
            lines.add("END:VCALENDAR");

            StringBuilder ics = new StringBuilder();
            for (String line : lines) {
                ics.append(fold(line)).append("\r\n");
            }
            return ics.toString();
        });
    }

    /** A TEXT value: backslashes, semicolons, commas and line breaks escaped. */
    static String text(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replace("\n", "\\n");
    }

    /** A parameter value such as CN: quoted, with no quotes or line breaks of its own. */
    static String parameter(String value) {
        String clean = value == null ? "" : value.replaceAll("[\"\\r\\n\\t]", " ").trim();
        return "\"" + (clean.isEmpty() ? "Guest" : clean) + "\"";
    }

    /** An address for a mailto: URI, with nothing that could end the line or the property. */
    private static String address(String email) {
        return email == null ? "" : email.replaceAll("[\\s;:,\"<>]", "");
    }

    /** Lines longer than 75 octets continue on the next line after a space, without splitting a character. */
    static String fold(String line) {
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= 75) return line;
        StringBuilder folded = new StringBuilder();
        int lineBytes = 0;
        int limit = 75;
        for (int i = 0; i < line.length(); ) {
            int codePoint = line.codePointAt(i);
            String character = new String(Character.toChars(codePoint));
            int size = character.getBytes(StandardCharsets.UTF_8).length;
            if (lineBytes + size > limit) {
                folded.append("\r\n ");
                lineBytes = 1;
                limit = 75;
            }
            folded.append(character);
            lineBytes += size;
            i += Character.charCount(codePoint);
        }
        return folded.toString();
    }
}
