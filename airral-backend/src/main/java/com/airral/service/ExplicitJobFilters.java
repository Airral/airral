package com.airral.service;

/**
 * The four filters a candidate sets by hand, carried down to the query.
 *
 * <p>They used to be applied in Java after retrieval, over whichever rows the
 * recency-ordered query happened to return first. That made every one of them
 * report on a window rather than on the corpus: with a catalogue whose newest
 * postings come from a source that publishes no pay, "Salary listed" returned 3
 * results out of 464 that actually had a salary, because the 461 others were
 * never fetched. The filter was not wrong so much as answering a different
 * question -- "which of the newest few hundred have pay" -- while presenting
 * itself as an answer about everything.
 *
 * <p>Grouped into a record rather than added as four more parameters because the
 * query method already takes seven, and because they travel together: whether a
 * filter is set decides both the SQL and the paging strategy.
 */
public record ExplicitJobFilters(
        String workMode,
        Boolean salaryPosted,
        String experienceLevel,
        Boolean visaFriendly) {

    private static final ExplicitJobFilters NONE = new ExplicitJobFilters(null, null, null, null);

    public static ExplicitJobFilters none() {
        return NONE;
    }

    public boolean any() {
        return hasWorkMode() || wantsPostedSalary() || hasExperienceLevel() || wantsVisaFriendly();
    }

    /** "all" and blank mean the candidate has not narrowed anything. */
    public boolean hasWorkMode() {
        return isSet(workMode);
    }

    public boolean hasExperienceLevel() {
        return isSet(experienceLevel);
    }

    public boolean wantsPostedSalary() {
        return Boolean.TRUE.equals(salaryPosted);
    }

    public boolean wantsVisaFriendly() {
        return Boolean.TRUE.equals(visaFriendly);
    }

    public String normalizedWorkMode() {
        return hasWorkMode() ? workMode.trim().toUpperCase(java.util.Locale.US) : null;
    }

    public String normalizedExperienceLevel() {
        return hasExperienceLevel() ? experienceLevel.trim().toLowerCase(java.util.Locale.US) : null;
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank() && !"all".equalsIgnoreCase(value.trim());
    }
}
