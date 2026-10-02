package com.airral.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CandidateJobPageResponse {
    private List<CandidateJobSummaryResponse> jobs;
    private int limit;
    private int offset;
    private boolean hasMore;
    private Integer nextOffset;
    /**
     * Present only for a signed-in, typed search whose matches the candidate's saved
     * target roles or location narrowed. The portal uses it to ask whether to search
     * past them, instead of silently showing fewer jobs than the search found.
     */
    private PreferenceNarrowing preferenceNarrowing;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PreferenceNarrowing {
        /** Postings the search found before preferences narrowed them. */
        private int matched;
        /** True when {@link #matched} hit the ranking window, so the real count is higher. */
        private boolean matchedIsLowerBound;
        /** Postings left once the preferences are applied. */
        private int shown;
        /** Postings the preferences remove; one outside both roles and location counts once. */
        private int hidden;
        private int hiddenByRoles;
        private int hiddenByLocation;
        /** The saved target roles, when they hid anything. */
        private List<String> targetRoles;
        /** The saved location, when it hid anything. */
        private String location;
        /** True when this request searched past the preferences (ignorePreferences=true). */
        private boolean ignored;
    }
}
