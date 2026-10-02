package com.airral.service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A place someone typed, read into the pieces a job location can be matched on.
 *
 * <p>The search box matched a place by accident: it is a text search over the
 * whole posting, so "texas" found jobs that mention Texas in the description, "co"
 * matched "San Francisco, <b>Co</b>mpany", and "colorado" missed every job written
 * "Denver, CO". This reads the input as a city, a state, or both, and builds a
 * match on the location column alone, in which a state is its name or its
 * abbreviation and nothing else:
 *
 * <pre>
 *   "denver"            city denver
 *   "colorado" / "co"   state CO
 *   "denver, co"        city denver and state CO
 *   "denver colorado"   the same
 *   "park meadows mall" text, matched as written
 * </pre>
 *
 * <p>Every value is bound as a parameter, and LIKE wildcards in the input are
 * escaped, so a typed "%" matches a percent sign and nothing more.
 */
public final class LocationFilter {

    private static final Map<String, String> STATE_NAMES = new LinkedHashMap<>();
    private static final Map<String, String> ABBREVIATION_TO_NAME = new LinkedHashMap<>();

    static {
        String[][] states = {
                {"AL", "alabama"}, {"AK", "alaska"}, {"AZ", "arizona"}, {"AR", "arkansas"},
                {"CA", "california"}, {"CO", "colorado"}, {"CT", "connecticut"}, {"DE", "delaware"},
                {"DC", "district of columbia"}, {"FL", "florida"}, {"GA", "georgia"}, {"HI", "hawaii"},
                {"ID", "idaho"}, {"IL", "illinois"}, {"IN", "indiana"}, {"IA", "iowa"},
                {"KS", "kansas"}, {"KY", "kentucky"}, {"LA", "louisiana"}, {"ME", "maine"},
                {"MD", "maryland"}, {"MA", "massachusetts"}, {"MI", "michigan"}, {"MN", "minnesota"},
                {"MS", "mississippi"}, {"MO", "missouri"}, {"MT", "montana"}, {"NE", "nebraska"},
                {"NV", "nevada"}, {"NH", "new hampshire"}, {"NJ", "new jersey"}, {"NM", "new mexico"},
                {"NY", "new york"}, {"NC", "north carolina"}, {"ND", "north dakota"}, {"OH", "ohio"},
                {"OK", "oklahoma"}, {"OR", "oregon"}, {"PA", "pennsylvania"}, {"RI", "rhode island"},
                {"SC", "south carolina"}, {"SD", "south dakota"}, {"TN", "tennessee"}, {"TX", "texas"},
                {"UT", "utah"}, {"VT", "vermont"}, {"VA", "virginia"}, {"WA", "washington"},
                {"WV", "west virginia"}, {"WI", "wisconsin"}, {"WY", "wyoming"},
        };
        for (String[] state : states) {
            STATE_NAMES.put(state[1], state[0]);
            ABBREVIATION_TO_NAME.put(state[0], state[1]);
        }
        STATE_NAMES.put("washington dc", "DC");
        STATE_NAMES.put("washington d c", "DC");
        STATE_NAMES.put("d c", "DC");
    }

    private static final java.util.Set<String> COUNTRIES =
            java.util.Set.of("united states of america", "united states", "usa", "u s a", "u s", "us");

    private static final Pattern COUNTRY_SUFFIX =
            Pattern.compile("(?:,\\s*|\\s+)(?:united states of america|united states|usa|u s a|u s|us)$");

    /** Lower-case city, or null. */
    private final String city;
    /** Two-letter state, or null. */
    private final String state;
    /**
     * Free text, when the input is neither a state nor a city plus a state: each
     * comma-separated part, all of which must appear. "london, united kingdom" is
     * two terms, because the postings write it with a comma.
     */
    private final java.util.List<String> terms;

    private LocationFilter(String city, String state, java.util.List<String> terms) {
        this.city = city;
        this.state = state;
        this.terms = terms;
    }

    /** Nothing to filter on: blank input. */
    public static Optional<LocationFilter> parse(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String s = input.toLowerCase(Locale.US)
                .replace(".", " ")
                .replaceAll("[\\s]+", " ")
                .trim();
        s = COUNTRY_SUFFIX.matcher(s).replaceFirst("").trim();
        s = s.replaceAll("^[,;\\s]+|[,;\\s]+$", "");
        // The country alone is "anywhere in it", not a place.
        if (COUNTRIES.contains(s)) {
            return Optional.empty();
        }
        if (s.isEmpty()) {
            return Optional.empty();
        }

        // "denver, co" and "denver, colorado": a comma separates the city from the state.
        if (s.contains(",")) {
            String[] parts = s.split("\\s*,\\s*");
            String stateGuess = parts[parts.length - 1];
            String cityGuess = String.join(" ", java.util.Arrays.copyOf(parts, parts.length - 1)).trim();
            String abbreviation = abbreviationOf(stateGuess);
            if (abbreviation != null && !cityGuess.isEmpty()) {
                return Optional.of(new LocationFilter(cityGuess, abbreviation, null));
            }
            java.util.List<String> terms = new java.util.ArrayList<>();
            for (String part : parts) {
                if (!part.isBlank()) {
                    terms.add(part.trim());
                }
            }
            return Optional.of(new LocationFilter(null, null, terms));
        }

        // A bare state, by name or abbreviation: "colorado", "co", "new york".
        String bare = abbreviationOf(s);
        if (bare != null) {
            return Optional.of(new LocationFilter(null, bare, null));
        }

        // "denver co", "denver colorado", "new york new york": the end of the
        // input is the state, and what is before it the city.
        String[] words = s.split(" ");
        for (int take = Math.min(2, words.length - 1); take >= 1; take--) {
            String tail = String.join(" ", java.util.Arrays.copyOfRange(words, words.length - take, words.length));
            String head = String.join(" ", java.util.Arrays.copyOfRange(words, 0, words.length - take));
            String abbreviation = abbreviationOf(tail);
            if (abbreviation != null && !head.isEmpty()) {
                return Optional.of(new LocationFilter(head, abbreviation, null));
            }
        }

        return Optional.of(new LocationFilter(s, null, null));
    }

    /** The two-letter state for a name or an abbreviation, or null. */
    static String abbreviationOf(String value) {
        String v = value.toLowerCase(Locale.US).replace(".", " ").replaceAll("\\s+", " ").trim();
        if (v.length() == 2 && ABBREVIATION_TO_NAME.containsKey(v.toUpperCase(Locale.US))) {
            return v.toUpperCase(Locale.US);
        }
        return STATE_NAMES.get(v);
    }

    /** The two-letter codes of the 50 states and DC. */
    static java.util.Set<String> abbreviations() {
        return ABBREVIATION_TO_NAME.keySet();
    }

    /** The full lower-case name of a state, for matching "Denver, Colorado". */
    static String nameOf(String abbreviation) {
        return ABBREVIATION_TO_NAME.get(abbreviation);
    }

    public String city() {
        return city;
    }

    public String state() {
        return state;
    }

    public java.util.List<String> terms() {
        return terms;
    }

    /**
     * The condition as " AND (...)" and its bound values. {@code column} is the
     * lower-cased location, for the city and the state's name; {@code rawColumn} is
     * the location as written, for the abbreviation, which is matched in capitals.
     * The names are prefixed so more than one filter can sit in a statement.
     */
    public Sql toSql(String column, String rawColumn, String prefix) {
        StringBuilder sql = new StringBuilder();
        Map<String, Object> binds = new LinkedHashMap<>();

        if (city != null) {
            sql.append(" AND ").append(column).append(" LIKE :").append(prefix).append("City ESCAPE '\\'");
            binds.put(prefix + "City", "%" + escapeLike(city) + "%");
        }
        if (state != null) {
            // The name ("Denver, Colorado") or the abbreviation as a word of its own,
            // in capitals as postings write it ("Denver, CO", "Hanover MD", "US - CO").
            // Capitals are what separate the state LA from the "La" in "La Jolla", and
            // CO from the "Co" in "Company". Measured on the active postings, "la"
            // as a bare word also matched 6 places that are not Louisiana for every
            // 325 that are.
            sql.append(" AND (").append(column).append(" LIKE :").append(prefix).append("StateName ESCAPE '\\'")
                    .append(" OR ").append(rawColumn).append(" ~ :").append(prefix).append("StateAbbr)");
            binds.put(prefix + "StateName", "%" + escapeLike(nameOf(state)) + "%");
            binds.put(prefix + "StateAbbr", abbreviationRegex(state));
        }
        if (terms != null) {
            for (int i = 0; i < terms.size(); i++) {
                sql.append(" AND ").append(column).append(" LIKE :").append(prefix).append("Term").append(i)
                        .append(" ESCAPE '\\'");
                binds.put(prefix + "Term" + i, "%" + escapeLike(terms.get(i)) + "%");
            }
        }
        return new Sql(sql.toString(), binds);
    }

    /**
     * Whether a location as written satisfies this filter: the same rule as the SQL,
     * for lists that are narrowed in memory (a signed-in feed merges several
     * retrievals, one of which cannot take a location).
     */
    public boolean matches(String location) {
        if (location == null) {
            return false;
        }
        String lower = location.toLowerCase(Locale.US);
        if (city != null && !lower.contains(city)) {
            return false;
        }
        if (state != null
                && !lower.contains(nameOf(state))
                && !Pattern.compile(abbreviationRegex(state)).matcher(location).find()) {
            return false;
        }
        if (terms != null) {
            for (String term : terms) {
                if (!lower.contains(term)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** A condition and the values it binds. */
    public record Sql(String condition, Map<String, Object> binds) {
    }

    /**
     * The abbreviation as a whole word, in capitals: "CO" in "Denver, CO", but not
     * "Co" in "Company", "La" in "La Jolla", or "co" in "Colorado".
     */
    static String abbreviationRegex(String abbreviation) {
        String letters = "DC".equals(abbreviation) ? "D\\.?C\\.?" : abbreviation;
        return "(^|[^A-Za-z])" + letters + "([^A-Za-z]|$)";
    }

    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @Override
    public String toString() {
        return "LocationFilter[city=" + city + ", state=" + state + ", terms=" + terms + "]";
    }
}
