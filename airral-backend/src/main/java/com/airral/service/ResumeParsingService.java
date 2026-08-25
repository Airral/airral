package com.airral.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ResumeParsingService {

    private static final int MAX_EXTRACTED_TEXT_CHARS = 120_000;
    private static final int MAX_SUMMARY_CHARS = 650;
    private static final int MAX_DESCRIPTION_CHARS = 2_000;
    private static final Pattern EMAIL_PATTERN = Pattern.compile("(?i)\\b[\\w.%+-]+@[\\w.-]+\\.[a-z]{2,}\\b");
    private static final Pattern PHONE_PATTERN = Pattern.compile("(?i)(?:\\+?1[-.\\s]?)?\\(?\\d{3}\\)?[-.\\s]?\\d{3}[-.\\s]?\\d{4}");
    private static final Pattern DATE_RANGE_PATTERN = Pattern.compile(
            "(?i)\\b((?:(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\s+)?\\d{4})\\s*(?:-|\\u2013|\\u2014|to)\\s*((?:(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\s+)?\\d{4}|present|current|now)\\b"
    );
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(19|20)\\d{2}\\b");
    private static final List<String> SECTION_BREAK_HEADERS = List.of(
            "Professional Summary",
            "Professional Experience",
            "Employment History",
            "Technical Skills",
            "Core Skills",
            "Work Experience",
            "Certifications",
            "Publications",
            "Education",
            "Leadership",
            "Projects",
            "Summary",
            "Objective",
            "Awards"
    );
    private static final Pattern SECTION_BREAK_PATTERN = Pattern.compile(
            "(?i)\\b(" + SECTION_BREAK_HEADERS.stream().map(Pattern::quote).collect(Collectors.joining("|")) + ")\\b"
    );
    private static final Set<String> SECTION_HEADERS = Set.of(
            "summary",
            "professional summary",
            "profile",
            "objective",
            "core skills",
            "technical skills",
            "skills",
            "professional experience",
            "experience",
            "work experience",
            "employment history",
            "leadership",
            "projects",
            "education",
            "awards",
            "certifications",
            "volunteer",
            "publications"
    );
    private static final Set<String> SUMMARY_HEADERS = Set.of("summary", "professional summary", "profile", "objective");
    private static final Set<String> SKILL_HEADERS = Set.of("core skills", "technical skills", "skills");
    private static final Set<String> EXPERIENCE_HEADERS = Set.of("professional experience", "experience", "work experience", "employment history");
    private static final Set<String> EDUCATION_HEADERS = Set.of("education");
    private static final Set<String> SUMMARY_END_HEADERS = Set.of(
            "core skills", "technical skills", "skills", "professional experience", "experience", "work experience",
            "employment history", "education", "projects", "leadership", "awards", "certifications"
    );
    private static final Set<String> SKILL_END_HEADERS = Set.of(
            "professional experience", "experience", "work experience", "employment history", "education", "projects",
            "leadership", "awards", "certifications", "summary", "professional summary"
    );
    private static final Set<String> EXPERIENCE_END_HEADERS = Set.of(
            "education", "projects", "leadership", "awards", "certifications", "skills", "core skills",
            "technical skills", "summary", "professional summary", "volunteer", "publications"
    );
    private static final Set<String> EDUCATION_END_HEADERS = Set.of(
            "experience", "professional experience", "work experience", "employment history", "projects",
            "leadership", "awards", "certifications", "skills", "core skills", "technical skills",
            "summary", "professional summary", "volunteer", "publications"
    );
    public Mono<ParsedResume> parse(Resource resource, String fileExtension) {
        return Mono.fromCallable(() -> {
            String extension = fileExtension == null ? "" : fileExtension.toLowerCase(Locale.US);
            String rawText;
            if (extension.endsWith("pdf")) {
                rawText = extractPdf(resource);
            } else if (extension.endsWith("docx")) {
                rawText = extractDocx(resource);
            } else {
                throw new IllegalArgumentException("Unsupported resume file type");
            }

            String extractedText = cap(normalizeText(rawText));
            if (wordCount(extractedText) < 20) {
                throw new IllegalArgumentException(
                        "The resume contains too little readable text. Upload a text-based PDF or DOCX instead of a scanned image.");
            }
            List<Map<String, Object>> experience = extractExperience(extractedText);
            List<Map<String, Object>> education = extractEducation(extractedText);
            List<String> skills = extractSkills(extractedText);
            String summary = extractSummary(extractedText);
            String headline = deriveHeadline(extractedText, experience);
            Map<String, Object> parsedProfile = new LinkedHashMap<>();
            putIfPresent(parsedProfile, "name", firstLikelyName(extractedText));
            putIfPresent(parsedProfile, "email", firstMatch(EMAIL_PATTERN, extractedText));
            putIfPresent(parsedProfile, "phone", firstMatch(PHONE_PATTERN, extractedText));
            putIfPresent(parsedProfile, "headline", headline);
            putIfPresent(parsedProfile, "summary", summary);
            putIfPresent(parsedProfile, "location", extractHeaderLocation(extractedText));
            parsedProfile.put("wordCount", wordCount(extractedText));
            parsedProfile.put("detectedSections", detectedSections(extractedText));
            int experienceMonths = estimateExperienceMonths(experience);
            parsedProfile.put("totalExperienceMonths", experienceMonths);
            parsedProfile.put("experienceYears", Math.round((experienceMonths / 12.0) * 10.0) / 10.0);
            parsedProfile.put("recentTitles", experience.stream()
                    .map(entry -> entry.get("title"))
                    .filter(java.util.Objects::nonNull)
                    .map(Object::toString)
                    .filter(value -> !value.isBlank())
                    .limit(5)
                    .toList());
            parsedProfile.put("skillEvidence", buildSkillEvidence(extractedText, skills));
            List<String> warnings = parseWarnings(extractedText, skills, experience, education);
            parsedProfile.put("parseWarnings", warnings);
            parsedProfile.put("parseConfidenceScore", parseConfidence(extractedText, skills, experience, education));
            parsedProfile.put("parserVersion", "resume-parser-v3");
            parsedProfile.put("parsedAt", LocalDateTime.now().toString());

            return new ParsedResume(
                    extractedText,
                    skills,
                    experience,
                    education,
                    parsedProfile
            );
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private String extractPdf(Resource resource) throws Exception {
        try (InputStream inputStream = resource.getInputStream();
             PDDocument document = Loader.loadPDF(inputStream.readAllBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String sortedText = stripper.getText(document);
            if (wordCount(sortedText) >= 20) {
                return sortedText;
            }

            PDFTextStripper fallbackStripper = new PDFTextStripper();
            fallbackStripper.setSortByPosition(false);
            String fallbackText = fallbackStripper.getText(document);
            return wordCount(fallbackText) > wordCount(sortedText) ? fallbackText : sortedText;
        }
    }

    private String extractDocx(Resource resource) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (InputStream inputStream = resource.getInputStream();
             XWPFDocument document = new XWPFDocument(inputStream)) {
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    appendLine(builder, paragraph.getText());
                } else if (element instanceof XWPFTable table) {
                    for (XWPFTableRow row : table.getRows()) {
                        List<String> cells = row.getTableCells().stream()
                                .map(XWPFTableCell::getText)
                                .map(String::strip)
                                .filter(value -> !value.isBlank())
                                .toList();
                        appendLine(builder, String.join(" | ", cells));
                    }
                }
            }
        }
        return builder.toString();
    }

    private void appendLine(StringBuilder builder, String value) {
        if (value != null && !value.isBlank()) {
            builder.append(value.strip()).append('\n');
        }
    }

    private String normalizeText(String rawText) {
        if (rawText == null) {
            return "";
        }

        return rawText
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\t', ' ')
                .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "")
                .replaceAll("[ ]{2,}", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    private String cap(String value) {
        if (value == null || value.length() <= MAX_EXTRACTED_TEXT_CHARS) {
            return value;
        }
        return value.substring(0, MAX_EXTRACTED_TEXT_CHARS);
    }

    private List<String> extractSkills(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        Set<String> skills = new LinkedHashSet<>();
        ResumeSkillCatalog.findSkills(text).forEach(skill -> addSkill(skills, skill));
        // Keep explicit, user-authored skills even when the shared catalog does not know them yet.
        for (String skill : skillsFromSkillsSection(text)) {
            addSkill(skills, firstNonBlank(ResumeSkillCatalog.canonicalize(skill), skill));
        }
        return new ArrayList<>(skills);
    }

    private List<Map<String, Object>> extractExperience(String text) {
        String body = sectionBody(text, EXPERIENCE_HEADERS, EXPERIENCE_END_HEADERS);
        if (body.isBlank()) {
            return List.of();
        }

        List<Map<String, Object>> entries = new ArrayList<>();
        Map<String, Object> current = null;
        StringBuilder description = new StringBuilder();

        List<String> lines = nonBlankLines(body);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (isSectionHeader(line)) {
                break;
            }

            ExperienceHeader header = parseExperienceHeader(line);
            int consumedLines = 0;
            if (header == null && index + 1 < lines.size() && isDateOnlyLine(lines.get(index + 1))) {
                header = parseExperienceHeader(line + " " + lines.get(index + 1));
                consumedLines = header == null ? 0 : 1;
            }
            if (header == null && index + 2 < lines.size() && isDateOnlyLine(lines.get(index + 2))) {
                String companyAndLocation = lines.get(index + 1);
                header = parseExperienceHeader(line + " | " + companyAndLocation + " " + lines.get(index + 2));
                consumedLines = header == null ? 0 : 2;
            }
            if (header != null) {
                current = flushExperience(entries, current, description);
                description.setLength(0);
                current = new LinkedHashMap<>();
                current.put("company", header.company());
                current.put("title", header.title());
                current.put("startDate", header.startDate());
                current.put("endDate", header.endDate());
                if (isNotBlank(header.location())) {
                    current.put("location", header.location());
                }
                current.put("current", header.current());
                index += consumedLines;
            } else if (current != null) {
                appendDescription(description, line);
            }
        }

        flushExperience(entries, current, description);
        return entries;
    }

    private Map<String, Object> flushExperience(
            List<Map<String, Object>> entries,
            Map<String, Object> current,
            StringBuilder description) {
        if (current != null) {
            String value = cap(description.toString().strip(), MAX_DESCRIPTION_CHARS);
            if (isNotBlank(value)) {
                current.put("description", value);
            }
            entries.add(current);
        }
        return null;
    }

    private ExperienceHeader parseExperienceHeader(String line) {
        if (!isNotBlank(line)) {
            return null;
        }

        String cleanedLine = stripBullet(line);
        Matcher matcher = DATE_RANGE_PATTERN.matcher(cleanedLine);
        if (!matcher.find()) {
            return null;
        }

        String beforeDates = cleanedLine.substring(0, matcher.start()).strip();
        String startDate = matcher.group(1).strip();
        String endDate = matcher.group(2).strip();
        if (beforeDates.isBlank()) {
            return null;
        }

        String[] parts = beforeDates.split("\\|");
        String title;
        String company = null;
        String location = null;
        if (parts.length >= 2) {
            title = parts[0].strip();
            company = parts[1].strip();
            if (parts.length >= 3) {
                location = parts[2].strip();
            }
        } else {
            Matcher atMatcher = Pattern.compile("(?i)^(.+?)\\s+(?:at|@)\\s+(.+)$").matcher(beforeDates);
            if (!atMatcher.find()) {
                return null;
            }
            title = atMatcher.group(1).strip();
            company = atMatcher.group(2).strip();
        }

        if (!isNotBlank(title) || !isNotBlank(company) || title.length() > 100 || company.length() > 100) {
            return null;
        }

        boolean current = endDate.equalsIgnoreCase("present")
                || endDate.equalsIgnoreCase("current")
                || endDate.equalsIgnoreCase("now");
        return new ExperienceHeader(title, company, location, startDate, endDate, current);
    }

    private boolean isDateOnlyLine(String value) {
        if (!isNotBlank(value)) {
            return false;
        }
        Matcher matcher = DATE_RANGE_PATTERN.matcher(value.strip());
        return matcher.find() && matcher.start() == 0 && matcher.end() == value.strip().length();
    }

    private List<Map<String, Object>> extractEducation(String text) {
        String body = sectionBody(text, EDUCATION_HEADERS, EDUCATION_END_HEADERS);
        if (body.isBlank()) {
            return List.of();
        }

        List<Map<String, Object>> education = new ArrayList<>();
        for (String line : nonBlankLines(body)) {
            if (isSectionHeader(line)) {
                break;
            }
            Map<String, Object> entry = parseEducationLine(line);
            if (!entry.isEmpty()) {
                education.add(entry);
            }
        }
        return education;
    }

    private Map<String, Object> parseEducationLine(String line) {
        String value = stripBullet(line);
        if (!isNotBlank(value) || value.length() < 8) {
            return Map.of();
        }

        Integer graduationYear = null;
        Matcher yearMatcher = YEAR_PATTERN.matcher(value);
        while (yearMatcher.find()) {
            graduationYear = Integer.parseInt(yearMatcher.group());
        }

        String withoutYear = graduationYear == null
                ? value
                : value.replaceFirst("\\b" + graduationYear + "\\b", "").strip();
        String degree = null;
        String field = null;
        String school = null;

        int colonIndex = withoutYear.indexOf(':');
        if (colonIndex > 0) {
            degree = withoutYear.substring(0, colonIndex).strip();
            school = withoutYear.substring(colonIndex + 1).strip();
        } else if (withoutYear.contains("|")) {
            String[] parts = withoutYear.split("\\|");
            if (parts.length >= 2) {
                degree = parts[0].strip();
                school = parts[1].strip();
            }
        } else if (looksLikeSchool(withoutYear)) {
            school = withoutYear;
        }

        if (isNotBlank(degree)) {
            field = fieldFromDegree(degree);
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        putIfPresent(entry, "school", school);
        putIfPresent(entry, "degree", degree);
        putIfPresent(entry, "field", field);
        if (graduationYear != null) {
            entry.put("graduationYear", graduationYear);
        }
        return entry;
    }

    private String extractSummary(String text) {
        return cap(sectionBody(text, SUMMARY_HEADERS, SUMMARY_END_HEADERS).strip(), MAX_SUMMARY_CHARS);
    }

    private String deriveHeadline(String text, List<Map<String, Object>> experience) {
        if (experience != null && !experience.isEmpty()) {
            for (Map<String, Object> entry : experience) {
                Object current = entry.get("current");
                if (Boolean.TRUE.equals(current)) {
                    String headline = headlineFromExperience(entry);
                    if (isNotBlank(headline)) {
                        return headline;
                    }
                }
            }
            String headline = headlineFromExperience(experience.get(0));
            if (isNotBlank(headline)) {
                return headline;
            }
        }

        String summary = extractSummary(text);
        if (isNotBlank(summary)) {
            String firstSentence = summary.split("(?<=[.!?])\\s+", 2)[0].strip();
            Matcher matcher = Pattern.compile("(?i)^([a-z][a-z\\s/+.-]{2,60}?)(?:\\s+with\\b|\\s+specializing\\b|\\s+experienced\\b|[,.])").matcher(firstSentence);
            if (matcher.find()) {
                return titleCaseRole(matcher.group(1).strip());
            }
        }
        return firstLikelyHeadline(text);
    }

    private String headlineFromExperience(Map<String, Object> entry) {
        Object titleValue = entry.get("title");
        Object companyValue = entry.get("company");
        String title = titleValue == null ? null : titleValue.toString();
        String company = companyValue == null ? null : companyValue.toString();
        if (!isNotBlank(title)) {
            return null;
        }
        String headline = isNotBlank(company) ? title + " at " + company : title;
        return headline.length() <= 90 ? headline : title;
    }

    private List<String> skillsFromSkillsSection(String text) {
        String body = sectionBody(text, SKILL_HEADERS, SKILL_END_HEADERS);
        if (body.isBlank()) {
            return List.of();
        }

        List<String> skills = new ArrayList<>();
        for (String line : nonBlankLines(body)) {
            String value = stripBullet(line);
            int colonIndex = value.indexOf(':');
            if (colonIndex >= 0 && colonIndex < value.length() - 1) {
                value = value.substring(colonIndex + 1);
            }
            for (String token : value.split("[,;]")) {
                String skill = cleanSkill(token);
                if (isNotBlank(skill)) {
                    skills.add(skill);
                }
            }
        }
        return skills;
    }

    private String sectionBody(String text, Set<String> startHeaders, Set<String> endHeaders) {
        if (!isNotBlank(text)) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        boolean collecting = false;
        for (String line : nonBlankLines(addSectionBreaks(text))) {
            String normalizedHeader = normalizeHeader(line);
            if (!collecting) {
                String remainder = headerRemainder(line, startHeaders);
                if (remainder != null) {
                    collecting = true;
                    appendLine(builder, remainder);
                } else if (startHeaders.contains(normalizedHeader)) {
                    collecting = true;
                }
                continue;
            }

            if (endHeaders.contains(normalizedHeader)) {
                break;
            }
            appendLine(builder, line);
        }
        return builder.toString().strip();
    }

    private List<String> nonBlankLines(String text) {
        if (!isNotBlank(text)) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\\n")) {
            String value = line.strip();
            if (isNotBlank(value)) {
                lines.add(value);
            }
        }
        return lines;
    }

    private String addSectionBreaks(String text) {
        return SECTION_BREAK_PATTERN.matcher(text).replaceAll("\n$1\n");
    }

    private String headerRemainder(String line, Set<String> headers) {
        String lower = line.toLowerCase(Locale.US).strip();
        for (String header : headers) {
            if (lower.equals(header)) {
                return "";
            }
            if (lower.startsWith(header + " ")) {
                return line.substring(header.length()).strip();
            }
            if (lower.startsWith(header + ":")) {
                return line.substring(header.length() + 1).strip();
            }
        }
        return null;
    }

    private boolean isSectionHeader(String line) {
        return SECTION_HEADERS.contains(normalizeHeader(line));
    }

    private String normalizeHeader(String line) {
        if (line == null) {
            return "";
        }
        return line.toLowerCase(Locale.US)
                .replaceAll("[^a-z ]", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private String firstLikelyName(String text) {
        for (String line : nonBlankLines(text)) {
            String value = line.strip();
            if (value.length() < 2 || value.length() > 80) {
                continue;
            }
            String lower = value.toLowerCase(Locale.US);
            if (isSectionHeader(value)
                    || EMAIL_PATTERN.matcher(value).find()
                    || PHONE_PATTERN.matcher(value).find()
                    || lower.contains("linkedin")
                    || lower.contains("github")
                    || lower.startsWith("http")
                    || value.contains("|")
                    || looksLikeRoleHeadline(lower)) {
                continue;
            }
            return value;
        }
        return null;
    }

    private String extractHeaderLocation(String text) {
        for (String line : nonBlankLines(text).stream().limit(5).toList()) {
            for (String token : line.split("\\|")) {
                String value = token.strip();
                if (looksLikeLocation(value)) {
                    return value;
                }
            }
        }
        return null;
    }

    private boolean looksLikeLocation(String value) {
        if (!isNotBlank(value)) {
            return false;
        }
        String lower = value.toLowerCase(Locale.US);
        return value.length() <= 80
                && value.contains(",")
                && !EMAIL_PATTERN.matcher(value).find()
                && !PHONE_PATTERN.matcher(value).find()
                && !lower.contains("github")
                && !lower.contains("linkedin")
                && !lower.contains("remote")
                && !value.matches(".*\\d.*");
    }

    private String firstMatch(Pattern pattern, String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group().strip() : null;
    }

    private void appendDescription(StringBuilder builder, String line) {
        String value = stripBullet(line);
        if (!isNotBlank(value)) {
            return;
        }
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(value);
    }

    private String stripBullet(String value) {
        if (value == null) {
            return "";
        }
        return value.strip().replaceFirst("^[\\u2022*\\-]+\\s*", "").strip();
    }

    private String cleanSkill(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = stripBullet(value)
                .replaceAll("\\s+", " ")
                .replaceAll("^[/:]+|[/:]+$", "")
                .strip();
        if (cleaned.length() < 2 || cleaned.length() > 45) {
            return null;
        }
        String lower = cleaned.toLowerCase(Locale.US);
        if (lower.equals("and") || lower.equals("tools") || lower.equals("languages")) {
            return null;
        }
        return cleaned;
    }

    private void addSkill(Set<String> skills, String skill) {
        String cleaned = cleanSkill(skill);
        if (!isNotBlank(cleaned)) {
            return;
        }
        boolean exists = skills.stream().anyMatch(existing -> existing.equalsIgnoreCase(cleaned));
        if (!exists) {
            skills.add(cleaned);
        }
    }

    private String fieldFromDegree(String degree) {
        Matcher inMatcher = Pattern.compile("(?i)\\bin\\s+(.+)$").matcher(degree);
        if (inMatcher.find()) {
            return inMatcher.group(1).strip();
        }
        Matcher ofMatcher = Pattern.compile("(?i)\\bof\\s+(.+)$").matcher(degree);
        if (!ofMatcher.find()) {
            return null;
        }
        String field = ofMatcher.group(1).strip();
        return field.equalsIgnoreCase("science") ? null : field;
    }

    private boolean looksLikeSchool(String value) {
        String lower = value.toLowerCase(Locale.US);
        return lower.contains("university")
                || lower.contains("college")
                || lower.contains("institute")
                || lower.contains("school");
    }

    private String titleCaseRole(String value) {
        if (!isNotBlank(value)) {
            return null;
        }
        String[] words = value.toLowerCase(Locale.US).split("\\s+");
        StringBuilder builder = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return builder.toString();
    }

    private String firstLikelyHeadline(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return null;
        }

        String[] lines = rawText.replace("\r\n", "\n").replace('\r', '\n').split("\\n");
        for (String line : lines) {
            String value = line.strip();
            if (value.length() < 4 || value.length() > 90) {
                continue;
            }
            String lower = value.toLowerCase(Locale.US);
            if (lower.contains("resume") || lower.startsWith("http") || EMAIL_PATTERN.matcher(value).find() || PHONE_PATTERN.matcher(value).find()) {
                continue;
            }
            if (!looksLikeRoleHeadline(lower)) {
                continue;
            }
            return value;
        }
        return null;
    }

    private boolean looksLikeRoleHeadline(String value) {
        return value.contains("engineer")
                || value.contains("developer")
                || value.contains("designer")
                || value.contains("manager")
                || value.contains("analyst")
                || value.contains("specialist")
                || value.contains("associate")
                || value.contains("consultant")
                || value.contains("coordinator")
                || value.contains("director")
                || value.contains("lead")
                || value.contains("nurse")
                || value.contains("teacher")
                || value.contains("accountant")
                || value.contains("technician")
                || value.contains("operator")
                || value.contains("representative")
                || value.contains("recruiter")
                || value.contains("administrator");
    }

    private int wordCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.strip().split("\\s+").length;
    }

    private List<String> detectedSections(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        String lower = addSectionBreaks(text).toLowerCase(Locale.US);
        List<String> sections = new ArrayList<>();
        addIfPresent(sections, lower, "experience");
        addIfPresent(sections, lower, "education");
        addIfPresent(sections, lower, "skills");
        addIfPresent(sections, lower, "certifications");
        addIfPresent(sections, lower, "projects");
        addIfPresent(sections, lower, "summary");
        addIfPresent(sections, lower, "awards");
        addIfPresent(sections, lower, "leadership");
        return sections;
    }

    private void addIfPresent(List<String> sections, String text, String section) {
        if (text.contains(section)) {
            sections.add(section);
        }
    }

    private Map<String, Object> buildSkillEvidence(String text, List<String> skills) {
        String skillsSection = sectionBody(text, SKILL_HEADERS, SKILL_END_HEADERS);
        String experienceSection = sectionBody(text, EXPERIENCE_HEADERS, EXPERIENCE_END_HEADERS);
        Map<String, Object> evidence = new LinkedHashMap<>();
        for (String skill : skills) {
            List<String> sections = new ArrayList<>();
            if (skillAppearsIn(skillsSection, skill)) {
                sections.add("skills");
            }
            if (skillAppearsIn(experienceSection, skill)) {
                sections.add("experience");
            }
            if (sections.isEmpty()) {
                sections.add("other");
            }
            Map<String, Object> skillEvidence = new LinkedHashMap<>();
            skillEvidence.put("mentions", Math.max(1, ResumeSkillCatalog.mentionCount(text, skill)));
            skillEvidence.put("sections", sections);
            skillEvidence.put("confidence", sections.contains("experience") ? "HIGH" : "MEDIUM");
            evidence.put(skill, skillEvidence);
        }
        return evidence;
    }

    private boolean skillAppearsIn(String text, String skill) {
        return ResumeSkillCatalog.mentionCount(text, skill) > 0
                || ResumeSkillCatalog.containsPhrase(text, skill);
    }

    private List<String> parseWarnings(
            String text,
            List<String> skills,
            List<Map<String, Object>> experience,
            List<Map<String, Object>> education) {
        List<String> warnings = new ArrayList<>();
        int words = wordCount(text);
        if (words < 100) {
            warnings.add("Only " + words + " words were readable; review the extracted information before using job matches.");
        }
        if (skills.isEmpty()) {
            warnings.add("No skills were confidently detected.");
        }
        if (experience.isEmpty()) {
            warnings.add("Work experience entries were not confidently structured; dates and titles may need review.");
        }
        if (education.isEmpty()) {
            warnings.add("No education entries were confidently structured.");
        }
        return warnings;
    }

    private int parseConfidence(
            String text,
            List<String> skills,
            List<Map<String, Object>> experience,
            List<Map<String, Object>> education) {
        int score = wordCount(text) >= 100 ? 35 : 20;
        if (!skills.isEmpty()) score += 25;
        if (!experience.isEmpty()) score += 25;
        if (!education.isEmpty()) score += 10;
        if (firstMatch(EMAIL_PATTERN, text) != null) score += 5;
        return Math.min(100, score);
    }

    private int estimateExperienceMonths(List<Map<String, Object>> experience) {
        List<ExperiencePeriod> periods = new ArrayList<>();
        YearMonth now = YearMonth.now();
        for (Map<String, Object> entry : experience) {
            YearMonth start = parseResumeDate(stringValue(entry.get("startDate")), false);
            YearMonth end = parseResumeDate(stringValue(entry.get("endDate")), true);
            if (start != null && end != null && !end.isBefore(start)) {
                periods.add(new ExperiencePeriod(start, end.isAfter(now) ? now : end));
            }
        }
        periods.sort(java.util.Comparator.comparing(ExperiencePeriod::start));
        if (periods.isEmpty()) {
            return 0;
        }

        int months = 0;
        YearMonth mergedStart = periods.get(0).start();
        YearMonth mergedEnd = periods.get(0).end();
        for (int index = 1; index < periods.size(); index++) {
            ExperiencePeriod period = periods.get(index);
            if (!period.start().isAfter(mergedEnd.plusMonths(1))) {
                if (period.end().isAfter(mergedEnd)) {
                    mergedEnd = period.end();
                }
            } else {
                months += monthsInclusive(mergedStart, mergedEnd);
                mergedStart = period.start();
                mergedEnd = period.end();
            }
        }
        return months + monthsInclusive(mergedStart, mergedEnd);
    }

    private int monthsInclusive(YearMonth start, YearMonth end) {
        return (int) java.time.temporal.ChronoUnit.MONTHS.between(start, end) + 1;
    }

    private YearMonth parseResumeDate(String value, boolean allowPresent) {
        if (!isNotBlank(value)) {
            return null;
        }
        String normalized = value.strip();
        if (allowPresent && (normalized.equalsIgnoreCase("present")
                || normalized.equalsIgnoreCase("current")
                || normalized.equalsIgnoreCase("now"))) {
            return YearMonth.now();
        }
        for (String pattern : List.of("MMMM uuuu", "MMM uuuu", "uuuu")) {
            try {
                if (pattern.equals("uuuu")) {
                    return YearMonth.of(Integer.parseInt(normalized), 1);
                }
                return YearMonth.parse(normalized, DateTimeFormatter.ofPattern(pattern, Locale.US));
            } catch (DateTimeParseException | NumberFormatException ignored) {
                // Try the next supported resume date format.
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (isNotBlank(value)) {
                return value.strip();
            }
        }
        return null;
    }

    public record ParsedResume(
            String extractedText,
            List<String> skills,
            List<Map<String, Object>> experience,
            List<Map<String, Object>> education,
            Map<String, Object> parsedProfile) {
    }

    private record ExperiencePeriod(YearMonth start, YearMonth end) {
    }

    private record ExperienceHeader(
            String title,
            String company,
            String location,
            String startDate,
            String endDate,
            boolean current) {
    }

    private String cap(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars).strip();
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private void putIfPresent(Map<String, Object> map, String key, String value) {
        if (isNotBlank(value)) {
            map.put(key, value);
        }
    }
}
