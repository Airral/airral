package com.airral.service;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Classifies profile and job intent into broad role families.
 *
 * This is intentionally a taxonomy, not job-specific blocking. The matcher uses it
 * to avoid ranking unrelated tracks ahead of the user's stated or resume-derived role.
 */
final class RoleMatchClassifier {

    private static final Map<RoleFamily, List<String>> ROLE_PHRASES = new EnumMap<>(RoleFamily.class);
    private static final Map<RoleFamily, List<String>> SKILL_PHRASES = new EnumMap<>(RoleFamily.class);
    private static final Map<RoleFamily, Set<RoleFamily>> COMPATIBLE_FAMILIES = new EnumMap<>(RoleFamily.class);
    private static final List<String> NON_PEOPLE_MANAGER_TITLES = List.of(
            "product manager",
            "project manager",
            "program manager",
            "account manager",
            "customer success manager",
            "marketing manager",
            "social media manager");

    static {
        ROLE_PHRASES.put(RoleFamily.SOFTWARE_ENGINEERING, List.of(
                "software engineer", "software developer", "full stack", "frontend", "front end",
                "backend", "back end", "web developer", "application developer", "java developer",
                "python developer", "platform engineer", "site reliability", "sre", "devops",
                "cloud engineer", "mobile engineer", "android engineer", "ios engineer",
                "firmware engineer", "systems engineer", "qa engineer", "test automation engineer",
                "member of technical staff", "engineering manager"));
        ROLE_PHRASES.put(RoleFamily.DATA_ENGINEERING, List.of(
                "data engineer", "analytics engineer", "data platform engineer", "etl engineer",
                "big data engineer", "data infrastructure"));
        ROLE_PHRASES.put(RoleFamily.DATA_SCIENCE, List.of(
                "data scientist", "machine learning engineer", "ml engineer", "ai engineer",
                "applied scientist", "research scientist"));
        ROLE_PHRASES.put(RoleFamily.DATA_ANALYTICS, List.of(
                "data analyst", "business analyst", "analytics analyst", "people data analyst",
                "reporting analyst", "business intelligence"));
        ROLE_PHRASES.put(RoleFamily.SECURITY_ENGINEERING, List.of(
                "security engineer", "application security", "cloud security", "cybersecurity engineer",
                "security platform"));
        ROLE_PHRASES.put(RoleFamily.PRODUCT_MANAGEMENT, List.of(
                "product manager", "product owner", "product lead", "group product manager"));
        ROLE_PHRASES.put(RoleFamily.PROJECT_PROGRAM, List.of(
                "project manager", "program manager", "technical program manager", "scrum master"));
        ROLE_PHRASES.put(RoleFamily.SALES, List.of(
                "account executive", "sales", "business development", "revenue", "solutions consultant"));
        ROLE_PHRASES.put(RoleFamily.CUSTOMER_SUCCESS, List.of(
                "customer success", "customer experience", "customer support", "client success",
                "implementation consultant"));
        ROLE_PHRASES.put(RoleFamily.MARKETING, List.of("marketing", "content", "growth", "brand", "demand generation"));
        ROLE_PHRASES.put(RoleFamily.FINANCE, List.of("finance", "accounting", "accountant", "financial analyst", "controller"));
        ROLE_PHRASES.put(RoleFamily.PEOPLE_TALENT, List.of("recruiter", "talent acquisition", "people partner", "human resources", "hr "));
        ROLE_PHRASES.put(RoleFamily.LEGAL_COMPLIANCE, List.of("compliance", "risk advisor", "regulatory", "audit", "legal", "privacy counsel"));
        ROLE_PHRASES.put(RoleFamily.OPERATIONS, List.of("operations", "logistics", "supply chain", "procurement"));
        ROLE_PHRASES.put(RoleFamily.DESIGN, List.of("designer", "product design", "ux", "ui/ux", "user experience"));
        ROLE_PHRASES.put(RoleFamily.HEALTHCARE, List.of("nurse", "physician", "medical", "clinical", "pharmacist"));

        SKILL_PHRASES.put(RoleFamily.SOFTWARE_ENGINEERING, List.of(
                "java", "spring boot", "javascript", "typescript", "react", "angular", "node.js",
                "python", "docker", "kubernetes", "microservices", "distributed systems", "ci/cd",
                "github actions", "aws", "gcp", "rest api"));
        SKILL_PHRASES.put(RoleFamily.DATA_ENGINEERING, List.of(
                "spark", "airflow", "dbt", "bigquery", "snowflake", "data pipeline", "etl"));
        SKILL_PHRASES.put(RoleFamily.DATA_SCIENCE, List.of(
                "machine learning", "ml", "llm", "vertex ai", "pytorch", "tensorflow", "modeling"));
        SKILL_PHRASES.put(RoleFamily.DATA_ANALYTICS, List.of("tableau", "power bi", "analytics", "excel", "reporting"));
        SKILL_PHRASES.put(RoleFamily.SECURITY_ENGINEERING, List.of(
                "cybersecurity", "information security", "network security", "incident response", "soc 2", "iso 27001"));
        SKILL_PHRASES.put(RoleFamily.PRODUCT_MANAGEMENT, List.of("roadmap", "product strategy", "user research"));
        SKILL_PHRASES.put(RoleFamily.PROJECT_PROGRAM, List.of(
                "project management", "program management", "agile", "scrum", "change management"));
        SKILL_PHRASES.put(RoleFamily.DESIGN, List.of("figma", "wireframe", "prototype", "design systems"));
        SKILL_PHRASES.put(RoleFamily.SALES, List.of("salesforce", "pipeline", "quota", "prospecting"));
        SKILL_PHRASES.put(RoleFamily.CUSTOMER_SUCCESS, List.of(
                "customer success", "customer support", "account management"));
        SKILL_PHRASES.put(RoleFamily.MARKETING, List.of(
                "digital marketing", "content marketing", "seo", "sem", "google analytics", "market research"));
        SKILL_PHRASES.put(RoleFamily.FINANCE, List.of(
                "accounting", "financial analysis", "financial modeling", "forecasting", "budgeting", "gaap", "quickbooks"));
        SKILL_PHRASES.put(RoleFamily.PEOPLE_TALENT, List.of(
                "recruiting", "talent acquisition", "employee relations", "hris", "payroll"));
        SKILL_PHRASES.put(RoleFamily.LEGAL_COMPLIANCE, List.of(
                "compliance", "risk management", "audit", "regulatory compliance"));
        SKILL_PHRASES.put(RoleFamily.OPERATIONS, List.of(
                "supply chain", "logistics", "procurement", "inventory management", "lean six sigma"));
        SKILL_PHRASES.put(RoleFamily.HEALTHCARE, List.of(
                "patient care", "clinical research", "epic", "hipaa", "cpr"));

        compatible(RoleFamily.SOFTWARE_ENGINEERING,
                RoleFamily.DATA_ENGINEERING,
                RoleFamily.DATA_SCIENCE,
                RoleFamily.SECURITY_ENGINEERING);
        compatible(RoleFamily.DATA_ENGINEERING,
                RoleFamily.SOFTWARE_ENGINEERING,
                RoleFamily.DATA_SCIENCE,
                RoleFamily.DATA_ANALYTICS);
        compatible(RoleFamily.DATA_SCIENCE,
                RoleFamily.DATA_ENGINEERING,
                RoleFamily.DATA_ANALYTICS,
                RoleFamily.SOFTWARE_ENGINEERING);
        compatible(RoleFamily.DATA_ANALYTICS,
                RoleFamily.DATA_ENGINEERING,
                RoleFamily.DATA_SCIENCE);
        compatible(RoleFamily.SECURITY_ENGINEERING, RoleFamily.SOFTWARE_ENGINEERING);
        compatible(RoleFamily.PRODUCT_MANAGEMENT, RoleFamily.PROJECT_PROGRAM);
        compatible(RoleFamily.PROJECT_PROGRAM, RoleFamily.PRODUCT_MANAGEMENT);
        compatible(RoleFamily.CUSTOMER_SUCCESS, RoleFamily.SALES);
        compatible(RoleFamily.SALES, RoleFamily.CUSTOMER_SUCCESS);
    }

    RoleIntent classifyProfile(Set<String> targetRoles, String headline, Set<String> skills) {
        Set<RoleFamily> roleFamilies = classifyTerms(targetRoles, ROLE_PHRASES);
        RoleFamily headlineFamily = classifyOne(headline, ROLE_PHRASES);
        if (headlineFamily != RoleFamily.UNKNOWN) {
            roleFamilies.add(headlineFamily);
        }

        RoleTrack track = classifyTrack(targetRoles, headline);
        if (!roleFamilies.isEmpty()) {
            return new RoleIntent(roleFamilies, track);
        }

        return new RoleIntent(classifyTerms(skills, SKILL_PHRASES), track);
    }

    RoleIntent classifyJob(String title, String department, List<String> tags) {
        RoleTrack track = classifyTrack(title, department, tags);
        Set<RoleFamily> titleFamilies = classifyTerms(nonBlankValues(title), ROLE_PHRASES);
        if (!titleFamilies.isEmpty()) {
            return new RoleIntent(titleFamilies, track);
        }

        Set<RoleFamily> fallbackFamilies = new LinkedHashSet<>();
        fallbackFamilies.addAll(classifyTerms(nonBlankValues(department), ROLE_PHRASES));
        fallbackFamilies.addAll(classifyTerms(tags == null ? List.of() : tags, ROLE_PHRASES));

        String normalizedTitle = normalize(title);
        if (fallbackFamilies.isEmpty()
                && containsPhrase(normalizedTitle, "engineer")
                && containsPhrase(normalize(department), "engineering")) {
            fallbackFamilies.add(RoleFamily.SOFTWARE_ENGINEERING);
        }

        return new RoleIntent(fallbackFamilies, track);
    }

    boolean compatible(RoleIntent profileIntent, RoleIntent jobIntent) {
        if (profileIntent == null || !profileIntent.isKnown()) {
            return true;
        }
        if (jobIntent == null || !jobIntent.isKnown()) {
            return true;
        }

        for (RoleFamily profileFamily : profileIntent.families()) {
            for (RoleFamily jobFamily : jobIntent.families()) {
                if (profileFamily == jobFamily
                        || COMPATIBLE_FAMILIES.getOrDefault(profileFamily, Set.of()).contains(jobFamily)) {
                    return true;
                }
            }
        }
        return false;
    }

    boolean careerTrackCompatible(RoleIntent profileIntent, RoleIntent jobIntent) {
        if (profileIntent == null || jobIntent == null || !profileIntent.hasTrack() || !jobIntent.hasTrack()) {
            return true;
        }
        if (profileIntent.track() == jobIntent.track()) {
            return true;
        }
        if (profileIntent.track() == RoleTrack.INDIVIDUAL_CONTRIBUTOR
                && (jobIntent.track() == RoleTrack.PEOPLE_MANAGEMENT || jobIntent.track() == RoleTrack.EXECUTIVE)) {
            return false;
        }
        return jobIntent.track() != RoleTrack.EXECUTIVE;
    }

    String display(RoleIntent intent) {
        if (intent == null || !intent.isKnown()) {
            return "Target role";
        }
        return intent.families().stream()
                .findFirst()
                .map(RoleFamily::label)
                .orElse("Target role");
    }

    private Set<RoleFamily> classifyTerms(Iterable<String> values, Map<RoleFamily, List<String>> phrasesByFamily) {
        Set<RoleFamily> families = new LinkedHashSet<>();
        if (values == null) {
            return families;
        }

        for (String value : values) {
            RoleFamily family = classifyOne(value, phrasesByFamily);
            if (family != RoleFamily.UNKNOWN) {
                families.add(family);
            }
        }
        return families;
    }

    private RoleTrack classifyTrack(String title, String department, List<String> tags) {
        List<String> values = new java.util.ArrayList<>();
        if (title != null) {
            values.add(title);
        }
        if (department != null) {
            values.add(department);
        }
        if (tags != null) {
            values.addAll(tags);
        }
        return classifyTrack(values, null);
    }

    private RoleTrack classifyTrack(Iterable<String> values, String headline) {
        String text = normalize(join(values, headline));
        if (text.isBlank()) {
            return RoleTrack.UNKNOWN;
        }
        if (containsAnyPhrase(text, "chief", "vp", "vice president", "director", "head of")) {
            return RoleTrack.EXECUTIVE;
        }
        boolean nonPeopleManager = NON_PEOPLE_MANAGER_TITLES.stream().anyMatch(title -> containsPhrase(text, title));
        if (!nonPeopleManager && containsAnyPhrase(text,
                "engineering manager",
                "senior manager",
                "people manager",
                "manager")) {
            return RoleTrack.PEOPLE_MANAGEMENT;
        }
        if (containsAnyPhrase(text,
                "engineer",
                "developer",
                "architect",
                "analyst",
                "scientist",
                "designer",
                "specialist",
                "consultant",
                "sre",
                "devops",
                "account executive")) {
            return RoleTrack.INDIVIDUAL_CONTRIBUTOR;
        }
        return RoleTrack.UNKNOWN;
    }

    private String join(Iterable<String> values, String extra) {
        List<String> parts = new java.util.ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    parts.add(value);
                }
            }
        }
        if (extra != null && !extra.isBlank()) {
            parts.add(extra);
        }
        return String.join(" ", parts);
    }

    private List<String> nonBlankValues(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value);
    }

    private RoleFamily classifyOne(String value, Map<RoleFamily, List<String>> phrasesByFamily) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return RoleFamily.UNKNOWN;
        }

        for (Map.Entry<RoleFamily, List<String>> entry : phrasesByFamily.entrySet()) {
            for (String phrase : entry.getValue()) {
                if (containsPhrase(normalized, phrase)) {
                    return entry.getKey();
                }
            }
        }
        return RoleFamily.UNKNOWN;
    }

    private static void compatible(RoleFamily family, RoleFamily... compatibleFamilies) {
        Set<RoleFamily> families = COMPATIBLE_FAMILIES.computeIfAbsent(family, ignored -> EnumSet.noneOf(RoleFamily.class));
        families.add(family);
        families.addAll(List.of(compatibleFamilies));
    }

    private boolean containsPhrase(String normalizedText, String phrase) {
        if (normalizedText == null || normalizedText.isBlank() || phrase == null || phrase.isBlank()) {
            return false;
        }
        String normalizedPhrase = normalize(phrase);
        return Pattern.compile("(^|\\s)" + Pattern.quote(normalizedPhrase) + "(\\s|$)")
                .matcher(normalizedText)
                .find();
    }

    private boolean containsAnyPhrase(String normalizedText, String... phrases) {
        if (phrases == null) {
            return false;
        }
        for (String phrase : phrases) {
            if (containsPhrase(normalizedText, phrase)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.toLowerCase(Locale.US)
                .replace("&", " and ")
                .replaceAll("[^a-z0-9+#.]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    record RoleIntent(Set<RoleFamily> families, RoleTrack track) {
        RoleIntent(Set<RoleFamily> families) {
            this(families, RoleTrack.UNKNOWN);
        }

        RoleIntent {
            families = families == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(families));
            track = track == null ? RoleTrack.UNKNOWN : track;
        }

        boolean isKnown() {
            return !families.isEmpty();
        }

        boolean hasTrack() {
            return track != RoleTrack.UNKNOWN;
        }
    }

    enum RoleFamily {
        SOFTWARE_ENGINEERING("Software Engineering"),
        DATA_ENGINEERING("Data Engineering"),
        DATA_SCIENCE("Data Science"),
        DATA_ANALYTICS("Data Analytics"),
        SECURITY_ENGINEERING("Security Engineering"),
        PRODUCT_MANAGEMENT("Product Management"),
        PROJECT_PROGRAM("Project/Program"),
        SALES("Sales"),
        CUSTOMER_SUCCESS("Customer Success"),
        MARKETING("Marketing"),
        FINANCE("Finance"),
        PEOPLE_TALENT("People/Talent"),
        LEGAL_COMPLIANCE("Legal/Compliance"),
        OPERATIONS("Operations"),
        DESIGN("Design"),
        HEALTHCARE("Healthcare"),
        UNKNOWN("Unknown");

        private final String label;

        RoleFamily(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    enum RoleTrack {
        INDIVIDUAL_CONTRIBUTOR,
        PEOPLE_MANAGEMENT,
        EXECUTIVE,
        UNKNOWN
    }
}
