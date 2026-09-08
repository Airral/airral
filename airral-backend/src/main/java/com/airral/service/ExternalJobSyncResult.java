package com.airral.service;

public record ExternalJobSyncResult(
        String status,
        int sourcesCount,
        int jobsSeen,
        int jobsUpserted,
        /** Postings retired because their board stopped listing them. */
        long jobsRetired,
        long jobsExpired,
        long jobsPurged
) {
}
