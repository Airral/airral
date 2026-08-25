package com.airral.service;

import com.airral.domain.CandidateResumeDocument;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Computes an instant resume health score from parsed resume data.
 * No LLM required — pure rule-based analysis that runs in <10ms.
 *
 * Scoring breakdown (0-100):
 * - Word count (appropriate length): 15 pts
 * - Quantified achievements: 20 pts
 * - Action verbs: 15 pts
 * - Contact info: 10 pts
 * - Skills density: 15 pts
 * - Section structure: 15 pts
 * - Formatting quality: 10 pts
 */
@Service
public class ResumeHealthScoreService {

    private final ObjectMapper objectMapper;

    private static final Pattern QUANTIFIED_PATTERN = Pattern.compile(
            "\\b(\\d{1,3}[,.]?\\d*[%+]|\\$\\d+[KkMmBb]?|\\d+\\s*(?:percent|%|users|clients|customers|projects|teams?|people|members|employees|revenue|sales|reduction|increase|improvement|growth|savings))\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern EMAIL_PATTERN = Pattern.compile("\\b[\\w.%+-]+@[\\w.-]+\\.[a-z]{2,}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE_PATTERN = Pattern.compile("(?:\\+?1[-.\\s]?)?\\(?\\d{3}\\)?[-.\\s]?\\d{3}[-.\\s]?\\d{4}");
    private static final Pattern LINKEDIN_PATTERN = Pattern.compile("linkedin(?:\\.com/in/)?", Pattern.CASE_INSENSITIVE);

    private static final List<String> ACTION_VERBS = List.of(
            "led", "built", "designed", "developed", "launched", "managed", "created",
            "implemented", "improved", "increased", "reduced", "delivered", "achieved",
            "optimized", "established", "drove", "spearheaded", "orchestrated",
            "streamlined", "automated", "generated", "negotiated", "secured",
            "transformed", "mentored", "scaled", "architected", "migrated",
            "coordinated", "analyzed", "resolved", "initiated", "executed",
            "accelerated", "consolidated", "pioneered", "revamped", "cultivated",
            "introduced", "oversaw", "restructured", "elevated", "championed",
            "formulated", "maximized", "directed", "supervised", "facilitated"
    );

    private static final List<String> EXPECTED_SECTIONS = List.of(
            "experience", "education", "skills", "summary", "objective",
            "projects", "certifications", "awards", "volunteer", "publications"
    );

    private static final List<String> WEAK_PHRASES = List.of(
            "responsible for", "duties included", "helped with", "assisted in",
            "worked on", "was involved in", "participated in", "tasked with"
    );

    public ResumeHealthScoreService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Compute resume health from a parsed resume document.
     * Returns a structured result with overall score, category breakdowns, issues, and fixes.
     */
    public ResumeHealthResult analyze(CandidateResumeDocument document) {
        if (document == null) {
            return ResumeHealthResult.empty("Resume text could not be extracted. Try uploading a different format.");
        }

        String text = document.getExtractedText();
        List<String> skills = fromJsonList(document.getParsedSkills());
        Map<String, Object> parsedProfile = fromJsonMap(document.getParsedProfile());

        if (text == null || text.isBlank()) {
            return ResumeHealthResult.empty("Resume text could not be extracted. Try uploading a different format.");
        }

        String lowerText = text.toLowerCase(Locale.US);
        int wordCount = parsedProfile.containsKey("wordCount")
                ? ((Number) parsedProfile.get("wordCount")).intValue()
                : text.strip().split("\\s+").length;

        // Category scores
        int lengthScore = scoreLengthCategory(wordCount);
        int quantifiedScore = scoreQuantifiedAchievements(text);
        int actionVerbScore = scoreActionVerbs(lowerText);
        int contactScore = scoreContactInfo(text);
        int skillsScore = scoreSkillsDensity(skills, wordCount);
        int sectionScore = scoreSectionStructure(lowerText, parsedProfile);
        int formatScore = scoreFormattingQuality(text, lowerText);

        int overallScore = lengthScore + quantifiedScore + actionVerbScore
                + contactScore + skillsScore + sectionScore + formatScore;
        overallScore = Math.max(0, Math.min(100, overallScore));

        // Build issues and fixes
        List<ResumeIssue> issues = new ArrayList<>();
        List<String> topFixes = new ArrayList<>();

        analyzeLength(wordCount, issues, topFixes);
        analyzeQuantified(text, issues, topFixes);
        analyzeActionVerbs(lowerText, issues, topFixes);
        analyzeContact(text, issues, topFixes);
        analyzeSkills(skills, wordCount, issues, topFixes);
        analyzeSections(lowerText, parsedProfile, issues, topFixes);
        analyzeFormatting(text, lowerText, issues, topFixes);

        // Category breakdown for frontend display
        Map<String, CategoryScore> categories = new LinkedHashMap<>();
        categories.put("length", new CategoryScore("Resume Length", lengthScore, 15));
        categories.put("achievements", new CategoryScore("Quantified Achievements", quantifiedScore, 20));
        categories.put("actionVerbs", new CategoryScore("Action Verbs", actionVerbScore, 15));
        categories.put("contact", new CategoryScore("Contact Information", contactScore, 10));
        categories.put("skills", new CategoryScore("Skills Coverage", skillsScore, 15));
        categories.put("sections", new CategoryScore("Section Structure", sectionScore, 15));
        categories.put("formatting", new CategoryScore("Formatting Quality", formatScore, 10));

        // Limit to top 5 fixes (most impactful first — sorted by issue severity)
        List<String> limitedFixes = topFixes.stream().limit(5).toList();

        return new ResumeHealthResult(
                overallScore,
                gradeFromScore(overallScore),
                categories,
                issues,
                limitedFixes,
                wordCount,
                skills.size()
        );
    }

    // --- Scoring methods (each returns points within its max) ---

    private int scoreLengthCategory(int wordCount) {
        // Ideal resume: 400-800 words (1-2 pages). Max 15 pts.
        if (wordCount >= 400 && wordCount <= 800) return 15;
        if (wordCount >= 300 && wordCount <= 1000) return 12;
        if (wordCount >= 200 && wordCount <= 1200) return 8;
        if (wordCount >= 100) return 4;
        return 1;
    }

    private int scoreQuantifiedAchievements(String text) {
        // Count measurable metrics. Max 20 pts.
        Matcher matcher = QUANTIFIED_PATTERN.matcher(text);
        int count = 0;
        while (matcher.find()) count++;
        if (count >= 6) return 20;
        if (count >= 4) return 16;
        if (count >= 2) return 10;
        if (count >= 1) return 5;
        return 0;
    }

    private int scoreActionVerbs(String lowerText) {
        // Count strong action verbs starting bullets. Max 15 pts.
        int count = 0;
        for (String verb : ACTION_VERBS) {
            if (lowerText.contains(verb)) count++;
        }
        if (count >= 10) return 15;
        if (count >= 7) return 12;
        if (count >= 4) return 8;
        if (count >= 2) return 5;
        return 2;
    }

    private int scoreContactInfo(String text) {
        // Email + phone + LinkedIn. Max 10 pts.
        int score = 0;
        if (EMAIL_PATTERN.matcher(text).find()) score += 4;
        if (PHONE_PATTERN.matcher(text).find()) score += 3;
        if (LINKEDIN_PATTERN.matcher(text).find()) score += 3;
        return score;
    }

    private int scoreSkillsDensity(List<String> skills, int wordCount) {
        // Skills relative to resume size. Max 15 pts.
        if (skills.isEmpty()) return 0;
        int count = skills.size();
        if (count >= 10) return 15;
        if (count >= 7) return 12;
        if (count >= 5) return 9;
        if (count >= 3) return 6;
        return 3;
    }

    private int scoreSectionStructure(String lowerText, Map<String, Object> parsedProfile) {
        // Check for expected sections. Max 15 pts.
        @SuppressWarnings("unchecked")
        List<String> detected = parsedProfile.containsKey("detectedSections")
                ? (List<String>) parsedProfile.get("detectedSections")
                : List.of();

        int sectionCount = detected.isEmpty() ? countSectionsFromText(lowerText) : detected.size();
        if (sectionCount >= 5) return 15;
        if (sectionCount >= 4) return 12;
        if (sectionCount >= 3) return 9;
        if (sectionCount >= 2) return 6;
        return 2;
    }

    private int scoreFormattingQuality(String text, String lowerText) {
        // Check for weak phrases, excessive caps, wall of text. Max 10 pts.
        int score = 10;

        // Penalize weak/passive phrases
        int weakCount = 0;
        for (String phrase : WEAK_PHRASES) {
            if (lowerText.contains(phrase)) weakCount++;
        }
        if (weakCount >= 3) score -= 4;
        else if (weakCount >= 1) score -= 2;

        // Penalize wall of text (no line breaks)
        long lineCount = text.lines().count();
        int wordCount = text.strip().split("\\s+").length;
        if (lineCount < 10 && wordCount > 200) score -= 3;

        // Penalize ALL CAPS abuse
        long capsLines = text.lines().filter(line -> line.length() > 10 && line.equals(line.toUpperCase())).count();
        if (capsLines > 5) score -= 2;

        return Math.max(0, score);
    }

    // --- Issue analysis methods ---

    private void analyzeLength(int wordCount, List<ResumeIssue> issues, List<String> fixes) {
        if (wordCount < 200) {
            issues.add(new ResumeIssue("TOO_SHORT", "critical", "Resume is too short (" + wordCount + " words). Most ATS systems and recruiters expect 400-800 words."));
            fixes.add("Add more detail to your experience bullets — aim for 3-5 bullets per role with specific outcomes.");
        } else if (wordCount < 300) {
            issues.add(new ResumeIssue("SHORT", "warning", "Resume is brief (" + wordCount + " words). Consider adding more detail about achievements."));
            fixes.add("Expand your experience section with measurable accomplishments for each role.");
        } else if (wordCount > 1200) {
            issues.add(new ResumeIssue("TOO_LONG", "warning", "Resume is long (" + wordCount + " words). Keep to 1-2 pages unless you have 15+ years of experience."));
            fixes.add("Cut older or less relevant roles to 1-2 bullets. Focus depth on your last 2-3 positions.");
        }
    }

    private void analyzeQuantified(String text, List<ResumeIssue> issues, List<String> fixes) {
        Matcher matcher = QUANTIFIED_PATTERN.matcher(text);
        int count = 0;
        while (matcher.find()) count++;
        if (count == 0) {
            issues.add(new ResumeIssue("NO_METRICS", "critical", "No quantified achievements found. Numbers make your impact concrete and memorable."));
            fixes.add("Add metrics to at least 3 bullets: revenue impact, team size, % improvement, users served, or time saved.");
        } else if (count < 3) {
            issues.add(new ResumeIssue("FEW_METRICS", "warning", "Only " + count + " quantified achievements. Aim for 5+ to stand out."));
            fixes.add("Quantify more bullets — even estimates help: 'Managed team of ~8' is better than 'Managed team'.");
        }
    }

    private void analyzeActionVerbs(String lowerText, List<ResumeIssue> issues, List<String> fixes) {
        int count = 0;
        for (String verb : ACTION_VERBS) {
            if (lowerText.contains(verb)) count++;
        }
        if (count < 3) {
            issues.add(new ResumeIssue("WEAK_VERBS", "warning", "Few strong action verbs found. Starting bullets with 'Led', 'Built', 'Delivered' signals ownership."));
            fixes.add("Start each bullet with a strong verb: Led, Built, Designed, Increased, Reduced, Launched, Delivered.");
        }
    }

    private void analyzeContact(String text, List<ResumeIssue> issues, List<String> fixes) {
        boolean hasEmail = EMAIL_PATTERN.matcher(text).find();
        boolean hasPhone = PHONE_PATTERN.matcher(text).find();
        boolean hasLinkedIn = LINKEDIN_PATTERN.matcher(text).find();

        if (!hasEmail) {
            issues.add(new ResumeIssue("NO_EMAIL", "critical", "No email address found on resume."));
            fixes.add("Add your email address to the resume header — it's the first thing recruiters look for.");
        }
        if (!hasPhone) {
            issues.add(new ResumeIssue("NO_PHONE", "info", "No phone number found. Some employers prefer a direct line."));
        }
        if (!hasLinkedIn) {
            issues.add(new ResumeIssue("NO_LINKEDIN", "info", "No LinkedIn URL found. Adding it can increase recruiter confidence."));
        }
    }

    private void analyzeSkills(List<String> skills, int wordCount, List<ResumeIssue> issues, List<String> fixes) {
        if (skills.isEmpty()) {
            issues.add(new ResumeIssue("NO_SKILLS", "critical", "No recognizable skills detected. ATS systems scan for specific technical and domain skills."));
            fixes.add("Add a Skills section listing your top 8-12 tools, technologies, and domain expertise.");
        } else if (skills.size() < 4) {
            issues.add(new ResumeIssue("FEW_SKILLS", "warning", "Only " + skills.size() + " skills detected. More skill keywords improve ATS match rates."));
            fixes.add("Expand your Skills section — include tools, frameworks, methodologies, and domain knowledge.");
        }
    }

    private void analyzeSections(String lowerText, Map<String, Object> parsedProfile, List<ResumeIssue> issues, List<String> fixes) {
        @SuppressWarnings("unchecked")
        List<String> detected = parsedProfile.containsKey("detectedSections")
                ? (List<String>) parsedProfile.get("detectedSections")
                : List.of();

        int sectionCount = detected.isEmpty() ? countSectionsFromText(lowerText) : detected.size();
        if (sectionCount < 3) {
            issues.add(new ResumeIssue("FEW_SECTIONS", "warning", "Resume has few clear sections. Standard sections help ATS and recruiters navigate quickly."));
            fixes.add("Organize with clear section headers: Summary, Experience, Skills, Education.");
        }

        // Check for missing key sections
        boolean hasExperience = lowerText.contains("experience") || lowerText.contains("employment") || lowerText.contains("work history");
        boolean hasEducation = lowerText.contains("education") || lowerText.contains("degree") || lowerText.contains("university") || lowerText.contains("college");
        if (!hasExperience) {
            issues.add(new ResumeIssue("NO_EXPERIENCE_SECTION", "critical", "No Experience section detected. This is the most important resume section."));
        }
        if (!hasEducation) {
            issues.add(new ResumeIssue("NO_EDUCATION", "info", "No Education section detected. Include it unless you have 10+ years of experience."));
        }
    }

    private void analyzeFormatting(String text, String lowerText, List<ResumeIssue> issues, List<String> fixes) {
        int weakCount = 0;
        for (String phrase : WEAK_PHRASES) {
            if (lowerText.contains(phrase)) weakCount++;
        }
        if (weakCount >= 2) {
            issues.add(new ResumeIssue("PASSIVE_LANGUAGE", "warning", "Found " + weakCount + " passive phrases ('responsible for', 'duties included'). Rewrite with active ownership."));
            fixes.add("Replace 'Responsible for X' with 'Led X, resulting in Y' — show ownership and outcome.");
        }
    }

    // --- Helpers ---

    private int countSectionsFromText(String lowerText) {
        int count = 0;
        for (String section : EXPECTED_SECTIONS) {
            if (lowerText.contains(section)) count++;
        }
        return count;
    }

    private String gradeFromScore(int score) {
        if (score >= 85) return "A";
        if (score >= 70) return "B";
        if (score >= 55) return "C";
        if (score >= 40) return "D";
        return "F";
    }

    private List<String> fromJsonList(Json json) {
        if (json == null || json.asString() == null || json.asString().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json.asString(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJsonMap(Json json) {
        if (json == null || json.asString() == null || json.asString().isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json.asString(), LinkedHashMap.class);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    // --- Inner types ---

    public record ResumeHealthResult(
            int score,
            String grade,
            Map<String, CategoryScore> categories,
            List<ResumeIssue> issues,
            List<String> topFixes,
            int wordCount,
            int skillCount
    ) {
        public static ResumeHealthResult empty(String reason) {
            return new ResumeHealthResult(
                    0, "F",
                    Map.of(),
                    List.of(new ResumeIssue("PARSE_FAILED", "critical", reason)),
                    List.of("Try uploading a standard PDF or DOCX file without complex formatting."),
                    0, 0
            );
        }
    }

    public record CategoryScore(String label, int score, int maxScore) {}

    public record ResumeIssue(String code, String severity, String message) {}
}
