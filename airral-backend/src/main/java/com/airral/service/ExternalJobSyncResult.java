package com.airral.service;

public record ExternalJobSyncResult(
        String status,
        int sourcesCount,
        int jobsSeen,
        int jobsUpserted,
        long jobsExpired,
        long jobsPurged
) {
}
