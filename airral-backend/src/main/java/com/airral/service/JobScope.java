package com.airral.service;

import java.util.Set;

/**
 * The jobs a caller may work on inside their company. HR works on every job; a
 * hiring manager only on the jobs they are hiring manager on. Company scoping
 * still applies on top: this only ever narrows it.
 */
public final class JobScope {

    private static final JobScope WHOLE_COMPANY = new JobScope(null);

    /** Null means every job in the company. */
    private final Set<Long> jobIds;

    private JobScope(Set<Long> jobIds) {
        this.jobIds = jobIds;
    }

    public static JobScope wholeCompany() {
        return WHOLE_COMPANY;
    }

    public static JobScope only(Set<Long> jobIds) {
        return new JobScope(Set.copyOf(jobIds));
    }

    public boolean isWholeCompany() {
        return jobIds == null;
    }

    public boolean allows(Long jobId) {
        return jobIds == null || (jobId != null && jobIds.contains(jobId));
    }
}
