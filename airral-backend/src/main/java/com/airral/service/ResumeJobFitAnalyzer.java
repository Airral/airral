package com.airral.service;

import com.airral.domain.CandidateResumeDocument;
import com.airral.dto.response.CandidateJobDetailResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ResumeJobFitAnalyzer {

    private static final RoleMatchClassifier ROLE_CLASSIFIER = new RoleMatchClassifier();
    private static final Pattern EXPERIENCE_REQUIREMENT = Pattern.compile(
            "(?i)(?:at least|min(?:imum)?(?: of)?|requires?|have)?\\s*(\\d{1,2})\\+?\\s*(?:-|to)?\\s*(?:\\d{1,2}\\+?\\s*)?years?(?:\\s+of)?(?:\\s+(?:professional|relevant|industry|hands-on))?\\s+experience");
    private static final List<String> REQUIRED_CUES = List.of(
            "required", "requirements", "must have", "must-have", "minimum qualifications",
            "basic qualifications", "you have", "you bring", "we require");
    private static final List<String> PREFERRED_CUES = List.of(
            "preferred", "nice to have", "nice-to-have", "bonus", "desired", "a plus", "ideally");
    private static final List<String> WEAK_PHRASES = List.of(
            "responsible for", "duties included", "helped with", "assisted with", "worked on", "tasked with");

    private final ObjectMapper objectMapper;

    public ResumeJobFitAnalyzer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    FitAnalysis analyze(CandidateResumeDocument resume, CandidateJobDetailResponse job) {
        List<String> parsedSkills = fromJsonList(resume.getParsedSkills());
        Set<String> candidateSkills = new LinkedHashSet<>(parsedSkills);
        candidateSkills.addAll(ResumeSkillCatalog.findSkills(resume.getExtractedText()));

        Map<String, Object> parsedProfile = fromJsonMap(resume.getParsedProfile());
        String headline = stringValue(parsedProfile.get("headline"));
        Set<String> recentRoles = new LinkedHashSet<>(stringList(parsedProfile.get("recentTitles")));
        recentRoles.addAll(experienceTitles(resume.getParsedExperience()));
        if (headline != null) {
            recentRoles.add(headline);
        }

        List<SkillRequirement> requirements = extractSkillRequirements(job);
        List<String> matched = requirements.stream()
                .filter(requirement -> hasSkill(candidateSkills, requirement.skill()))
                .map(SkillRequirement::skill)
                .distinct()
                .limit(12)
                .toList();

        List<SkillRequirement> missingSkills = requirements.stream()
                .filter(requirement -> !hasSkill(candidateSkills, requirement.skill()))
                .toList();
        List<String> missing = missingSkills.stream()
                .map(requirement -> requirement.priority().label() + ": " + requirement.skill())
                .limit(12)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        Integer requestedYears = requestedExperienceYears(job.getDescriptionText());
        Double candidateYears = numberValue(parsedProfile.get("experienceYears"));
        if (requestedYears != null && (candidateYears == null || candidateYears + 0.25 < requestedYears)) {
            String candidateValue = candidateYears == null
                    ? "not confidently parsed"
                    : formatYears(candidateYears) + " years shown";
            missing.add("Experience: " + requestedYears + "+ years requested; " + candidateValue);
        } else if (requestedYears != null) {
            matched = appendDistinct(matched, "Experience: " + requestedYears + "+ years");
        }

        RoleScore roleScore = scoreRole(recentRoles, headline, candidateSkills, job);
        int fitScore = calculateFitScore(
                roleScore,
                requirements,
                candidateSkills,
                requestedYears,
                candidateYears);

        List<String> keywordGaps = missingSkills.stream()
                .filter(requirement -> requirement.priority() != RequirementPriority.PREFERRED)
                .map(SkillRequirement::skill)
                .distinct()
                .limit(6)
                .toList();
        List<String> weakBullets = findWeakBullets(resume.getExtractedText());
        List<String> rewrites = buildRewriteGuidance(weakBullets, keywordGaps, matched);
        List<String> checklist = buildChecklist(job, roleScore, keywordGaps, requestedYears, candidateYears, parsedProfile);

        return new FitAnalysis(fitScore, matched, missing, keywordGaps, weakBullets, rewrites, checklist);
    }

    private List<SkillRequirement> extractSkillRequirements(CandidateJobDetailResponse job) {
        String description = firstNonBlank(job.getDescriptionText(), "");
        Map<String, RequirementPriority> priorities = new LinkedHashMap<>();
        for (ResumeSkillCatalog.SkillSignal signal : ResumeSkillCatalog.signals()) {
            Matcher matcher = signal.pattern().matcher(description);
            while (matcher.find()) {
                RequirementPriority priority = priorityAround(description, matcher.start(), matcher.end());
                priorities.merge(signal.canonical(), priority, RequirementPriority::stronger);
            }
        }

        if (job.getTags() != null) {
            for (String tag : job.getTags()) {
                String canonical = ResumeSkillCatalog.canonicalize(tag);
                if (canonical != null) {
                    priorities.merge(canonical, RequirementPriority.CORE, RequirementPriority::stronger);
                }
            }
        }

        return priorities.entrySet().stream()
                .map(entry -> new SkillRequirement(entry.getKey(), entry.getValue()))
                .sorted(java.util.Comparator.comparingInt(entry -> entry.priority().rank()))
                .limit(20)
                .toList();
    }

    private RequirementPriority priorityAround(String text, int start, int end) {
        int lineStart = Math.max(0, text.lastIndexOf('\n', start));
        int lineEnd = text.indexOf('\n', end);
        if (lineEnd < 0) lineEnd = text.length();
        int contextStart = Math.max(lineStart, start - 140);
        int contextEnd = Math.min(lineEnd, end + 80);
        String context = text.substring(contextStart, contextEnd).toLowerCase(Locale.US);
        String precedingContext = text.substring(contextStart, start).toLowerCase(Locale.US);
        int lastRequiredCue = lastCueIndex(precedingContext, REQUIRED_CUES);
        int lastPreferredCue = lastCueIndex(precedingContext, PREFERRED_CUES);
        if (lastRequiredCue >= 0 || lastPreferredCue >= 0) {
            return lastPreferredCue > lastRequiredCue ? RequirementPriority.PREFERRED : RequirementPriority.REQUIRED;
        }
        if (containsAny(context, PREFERRED_CUES)) return RequirementPriority.PREFERRED;
        if (containsAny(context, REQUIRED_CUES)) return RequirementPriority.REQUIRED;
        return RequirementPriority.CORE;
    }

    private int lastCueIndex(String text, List<String> cues) {
        return cues.stream().mapToInt(text::lastIndexOf).max().orElse(-1);
    }

    private RoleScore scoreRole(
            Set<String> recentRoles,
            String headline,
            Set<String> candidateSkills,
            CandidateJobDetailResponse job) {
        RoleMatchClassifier.RoleIntent candidateIntent = ROLE_CLASSIFIER.classifyProfile(recentRoles, headline, candidateSkills);
        RoleMatchClassifier.RoleIntent jobIntent = ROLE_CLASSIFIER.classifyJob(job.getTitle(), job.getDepartment(), job.getTags());
        if (!candidateIntent.isKnown() || !jobIntent.isKnown()) {
            return new RoleScore(0.55, false, "Role alignment could not be fully verified from the resume and job title.");
        }
        boolean exactFamily = candidateIntent.families().stream().anyMatch(jobIntent.families()::contains);
        boolean compatible = ROLE_CLASSIFIER.compatible(candidateIntent, jobIntent);
        boolean trackCompatible = ROLE_CLASSIFIER.careerTrackCompatible(candidateIntent, jobIntent);
        if (exactFamily && trackCompatible) {
            return new RoleScore(1.0, true, "The job aligns with the candidate's recent role family and career track.");
        }
        if (compatible && trackCompatible) {
            return new RoleScore(0.75, true, "The job is in an adjacent, compatible role family.");
        }
        if (!trackCompatible) {
            return new RoleScore(0.15, true, "The job appears to use a different career track or seniority path.");
        }
        return new RoleScore(0.20, true, "The job appears to be outside the candidate's recent role family.");
    }

    private int calculateFitScore(
            RoleScore roleScore,
            List<SkillRequirement> requirements,
            Set<String> candidateSkills,
            Integer requestedYears,
            Double candidateYears) {
        double weightedScore = roleScore.value() * 30;
        double totalWeight = 30;

        if (!requirements.isEmpty()) {
            double available = requirements.stream().mapToInt(requirement -> requirement.priority().weight()).sum();
            double covered = requirements.stream()
                    .filter(requirement -> hasSkill(candidateSkills, requirement.skill()))
                    .mapToInt(requirement -> requirement.priority().weight())
                    .sum();
            weightedScore += (covered / available) * 55;
            totalWeight += 55;
        }

        if (requestedYears != null) {
            double experienceCoverage;
            if (candidateYears == null) {
                experienceCoverage = 0.45;
            } else if (candidateYears >= requestedYears) {
                experienceCoverage = 1.0;
            } else {
                experienceCoverage = Math.max(0.10, (candidateYears / requestedYears) * 0.80);
            }
            weightedScore += experienceCoverage * 15;
            totalWeight += 15;
        }

        if (totalWeight == 30 && !roleScore.known()) {
            return 50;
        }
        int score = (int) Math.round((weightedScore / totalWeight) * 100);
        return Math.max(10, Math.min(96, score));
    }

    private Integer requestedExperienceYears(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        Matcher matcher = EXPERIENCE_REQUIREMENT.matcher(description);
        Integer highestMinimum = null;
        while (matcher.find()) {
            int value = Integer.parseInt(matcher.group(1));
            if (value <= 20 && (highestMinimum == null || value > highestMinimum)) {
                highestMinimum = value;
            }
        }
        return highestMinimum;
    }

    private List<String> findWeakBullets(String resumeText) {
        if (resumeText == null || resumeText.isBlank()) {
            return List.of();
        }
        List<String> guidance = new ArrayList<>();
        for (String line : resumeText.lines().map(String::strip).toList()) {
            String lower = line.toLowerCase(Locale.US);
            if (line.length() < 20 || line.length() > 300 || !containsAny(lower, WEAK_PHRASES)) {
                continue;
            }
            String excerpt = line.length() > 90 ? line.substring(0, 87).strip() + "..." : line;
            guidance.add("Strengthen \"" + excerpt + "\" with the action you owned, its scale, and a measurable result.");
            if (guidance.size() == 3) break;
        }
        return guidance;
    }

    private List<String> buildRewriteGuidance(
            List<String> weakBullets,
            List<String> keywordGaps,
            List<String> matched) {
        List<String> guidance = new ArrayList<>();
        guidance.addAll(weakBullets.stream().limit(2).toList());
        if (!keywordGaps.isEmpty()) {
            guidance.add("Only if accurate, add evidence for " + String.join(", ", keywordGaps.stream().limit(3).toList())
                    + " using: action + tool + scale + result. Otherwise, treat these as learning gaps.");
        } else if (!matched.isEmpty()) {
            guidance.add("Move the strongest evidence for " + String.join(", ", matched.stream().limit(3).toList())
                    + " into recent achievement bullets and quantify the outcome.");
        }
        return guidance.stream().distinct().limit(3).toList();
    }

    private List<String> buildChecklist(
            CandidateJobDetailResponse job,
            RoleScore roleScore,
            List<String> keywordGaps,
            Integer requestedYears,
            Double candidateYears,
            Map<String, Object> parsedProfile) {
        List<String> checklist = new ArrayList<>();
        checklist.add(roleScore.reason());
        if (!keywordGaps.isEmpty()) {
            checklist.add("Verify whether you genuinely have evidence for: "
                    + String.join(", ", keywordGaps.stream().limit(3).toList()) + ".");
        }
        if (requestedYears != null && (candidateYears == null || candidateYears < requestedYears)) {
            checklist.add("Decide whether your depth and outcomes offset the " + requestedYears + "+ year request.");
        }
        Number parseConfidence = parsedProfile.get("parseConfidenceScore") instanceof Number number ? number : null;
        if (parseConfidence == null || parseConfidence.intValue() < 70) {
            checklist.add("Review the extracted resume fields; parser confidence is limited for this document.");
        }
        if (job.getVisaConfidenceScore() != null && job.getVisaConfidenceScore() < 50) {
            checklist.add("Review work authorization language before investing time.");
        }
        checklist.add("Apply through the official source and track a follow-up date.");
        return checklist.stream().distinct().limit(6).toList();
    }

    private boolean hasSkill(Set<String> candidateSkills, String requirement) {
        return candidateSkills.stream().anyMatch(skill -> skill.equalsIgnoreCase(requirement));
    }

    private boolean containsAny(String text, List<String> phrases) {
        return phrases.stream().anyMatch(text::contains);
    }

    private List<String> appendDistinct(List<String> values, String value) {
        List<String> result = new ArrayList<>(values);
        if (!result.contains(value)) result.add(value);
        return result;
    }

    private Set<String> experienceTitles(Json json) {
        if (json == null || json.asString() == null || json.asString().isBlank()) {
            return Set.of();
        }
        try {
            List<Map<String, Object>> entries = objectMapper.readValue(
                    json.asString(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
            Set<String> titles = new LinkedHashSet<>();
            for (Map<String, Object> entry : entries) {
                String title = stringValue(entry.get("title"));
                if (title != null) titles.add(title);
            }
            return titles;
        } catch (JsonProcessingException error) {
            return Set.of();
        }
    }

    private List<String> fromJsonList(Json json) {
        if (json == null || json.asString() == null || json.asString().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(
                    json.asString(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException error) {
            return List.of();
        }
    }

    private Map<String, Object> fromJsonMap(Json json) {
        if (json == null || json.asString() == null || json.asString().isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(
                    json.asString(),
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        } catch (JsonProcessingException error) {
            return Map.of();
        }
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(java.util.Objects::nonNull).map(Object::toString).filter(item -> !item.isBlank()).toList();
    }

    private Double numberValue(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value == null) return null;
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private String stringValue(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        return value.toString().strip();
    }

    private String formatYears(double years) {
        return Math.abs(years - Math.rint(years)) < 0.05
                ? Integer.toString((int) Math.rint(years))
                : String.format(Locale.US, "%.1f", years);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.strip();
        }
        return "";
    }

    record FitAnalysis(
            int fitScore,
            List<String> matchedRequirements,
            List<String> missingRequirements,
            List<String> keywordGaps,
            List<String> weakBullets,
            List<String> suggestedRewrites,
            List<String> applicationChecklist) {
    }

    private record SkillRequirement(String skill, RequirementPriority priority) {
    }

    private record RoleScore(double value, boolean known, String reason) {
    }

    private enum RequirementPriority {
        REQUIRED("Required", 3, 0),
        CORE("Core", 2, 1),
        PREFERRED("Preferred", 1, 2);

        private final String label;
        private final int weight;
        private final int rank;

        RequirementPriority(String label, int weight, int rank) {
            this.label = label;
            this.weight = weight;
            this.rank = rank;
        }

        String label() { return label; }
        int weight() { return weight; }
        int rank() { return rank; }
        static RequirementPriority stronger(RequirementPriority left, RequirementPriority right) {
            return left.rank <= right.rank ? left : right;
        }
    }
}
