package com.airral.service;

import com.airral.domain.Organization;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * When an offer must be answered by: the end of a day in the company's own
 * time zone.
 *
 * <p>The server keeps local timestamps in its own zone, UTC in production. An
 * offer open for seven days used to close seven days to the minute after HR
 * pressed Send, which could be mid-afternoon for the candidate, and the date
 * went out with no zone, so a browser read it as its own local time.
 */
final class OfferDeadline {

    /** The day's last second. Postgres keeps microseconds, so LocalTime.MAX would round into the next day. */
    static final LocalTime END_OF_DAY = LocalTime.of(23, 59, 59);

    private OfferDeadline() {
    }

    /** The end of the day {@code days} from today in the company's zone, as a timestamp in {@code now}'s zone. */
    static LocalDateTime endOfDay(int days, Organization company, ZonedDateTime now) {
        ZoneId zone = zoneOf(company);
        return now.withZoneSameInstant(zone).toLocalDate().plusDays(days).atTime(END_OF_DAY)
                .atZone(zone)
                .withZoneSameInstant(now.getZone())
                .toLocalDateTime();
    }

    /** The company's time zone, or UTC when it has none Java knows. */
    static ZoneId zoneOf(Organization company) {
        String zone = company == null ? null : company.getTimezone();
        if (zone != null && !zone.isBlank()) {
            try {
                return ZoneId.of(zone.trim());
            } catch (DateTimeException ignored) {
                // A zone typed before the company profile checked them.
            }
        }
        return ZoneOffset.UTC;
    }

    /** A server timestamp with its offset, so every browser reads the same moment. */
    static OffsetDateTime withOffset(LocalDateTime serverTime) {
        return serverTime == null ? null : serverTime.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    /** A server timestamp in the company's zone, for text such as an email. */
    static ZonedDateTime inCompanyZone(LocalDateTime serverTime, Organization company) {
        return serverTime.atZone(ZoneId.systemDefault()).withZoneSameInstant(zoneOf(company));
    }
}
