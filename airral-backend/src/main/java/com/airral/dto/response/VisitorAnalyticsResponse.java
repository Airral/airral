package com.airral.dto.response;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What the visitor tables can actually say when someone asks "did anyone come".
 *
 * <p>V28 added the writes and no read, so the only way to answer that was to
 * open Cloud Console and type SQL. This is that SQL, shaped.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VisitorAnalyticsResponse {

    /** How far back this answer looks, after clamping. */
    private int windowDays;
    private OffsetDateTime since;

    private long totalEvents;
    private long pageViews;

    /**
     * Distinct visitor keys over the window, which is a count of person-days
     * rather than of people.
     *
     * <p>The key is salted with the UTC date on purpose, so the same person is a
     * different key tomorrow. A window-wide count of people is therefore not
     * something this data can produce, and pretending otherwise would be the
     * more useful-looking of two numbers and the wrong one. For "how many came
     * today", read the first entry of {@code daily}.
     */
    private long uniqueVisitorDays;

    /** Everyone on the list, not just the ones inside the window. */
    private long emailSignups;
    private long emailSignupsInWindow;

    /** Most recent day first, and days with no traffic are simply absent. */
    private List<DailyCount> daily;
    private List<NamedCount> byEvent;
    private List<NamedCount> topPaths;
    private List<NamedCount> topReferrers;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyCount {
        /** A UTC day, matching the day the visitor key is salted with. */
        private LocalDate day;
        private long events;
        private long pageViews;
        private long uniqueVisitors;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NamedCount {
        private String name;
        private long count;
    }
}
