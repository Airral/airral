package com.airral.service;

import com.airral.domain.Job;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a job asks for, and which of it an application shows.
 *
 * <p>The job's ATS keywords when HR set them. Otherwise the skills its
 * description and requirements name, so a job without keywords still gets
 * evidence. An application shows a keyword when its resume or its cover letter
 * mentions it as a whole word, so "Java" is not found in "JavaScript", and a
 * known skill counts under any of its names, so "k8s" finds Kubernetes.
 *
 * <p>This is evidence for the hiring team to check, never a gate: nothing
 * hides an application because of it.
 */
final class JobAlignment {

    /** The score an application gets when its job names nothing to look for. */
    static final int NO_KEYWORDS_SCORE = 75;

    record Result(List<String> matched, List<String> missing, int score) {
    }

    /** What an application's keywords were looked for in. */
    enum Source {
        /** The attached resume, and the note. */
        RESUME_AND_NOTE,
        /** Only the note: no resume document, as for a candidate added with a link. */
        NOTE,
        /** Only the note, because the attached resume's text could not be read. */
        UNREADABLE_RESUME
    }

    /** The text an application is read against, and where it came from. */
    record Text(String value, Source source) {
    }

    private JobAlignment() {
    }

    static Result of(Job job, String applicationText) {
        List<String> keywords = keywordsFor(job);
        if (keywords.isEmpty()) {
            return new Result(List.of(), List.of(), NO_KEYWORDS_SCORE);
        }
        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String keyword : keywords) {
            (mentions(applicationText, keyword) ? matched : missing).add(keyword);
        }
        return new Result(matched, missing, matched.size() * 100 / keywords.size());
    }

    /** The job's own keywords, each once, or the skills its description and requirements name. */
    static List<String> keywordsFor(Job job) {
        if (job.getAtsKeywords() != null) {
            Map<String, String> distinct = new LinkedHashMap<>();
            Arrays.stream(job.getAtsKeywords())
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(keyword -> !keyword.isEmpty())
                    .forEach(keyword -> distinct.putIfAbsent(keyword.toLowerCase(Locale.ROOT), keyword));
            if (!distinct.isEmpty()) {
                return new ArrayList<>(distinct.values());
            }
        }
        String jobText = String.join("\n",
                Optional.ofNullable(job.getDescription()).orElse(""),
                Optional.ofNullable(job.getRequirements()).orElse(""));
        return ResumeSkillCatalog.findSkills(jobText);
    }

    /**
     * Skills whose name is also an everyday word. The catalog leaves such names
     * out of its own patterns ("golang", not "go"), so as a keyword they count
     * only written as a name: "Go", not "go-live"; "Epic", not "an epic".
     */
    private static final Set<String> EVERYDAY_WORDS = Set.of("Go", "Epic");

    /**
     * Whether the text mentions the keyword. A keyword that is a known skill,
     * under any of its names, is found under all of them, and also as its own
     * words: the catalog's patterns leave some names out ("Leadership" looks for
     * "team leadership"), and a resume saying "leadership" still says it.
     * Anything else is found as the same words in the same order.
     */
    static boolean mentions(String text, String keyword) {
        if (text == null || text.isBlank() || keyword == null || keyword.isBlank()) {
            return false;
        }
        String wanted = keyword.strip();
        Optional<ResumeSkillCatalog.SkillSignal> known = ResumeSkillCatalog.signals().stream()
                .filter(signal -> signal.canonical().equalsIgnoreCase(wanted) || signal.pattern().matcher(wanted).matches())
                .findFirst();
        if (known.isEmpty()) {
            return ResumeSkillCatalog.containsPhrase(text, wanted);
        }
        ResumeSkillCatalog.SkillSignal signal = known.get();
        if (signal.pattern().matcher(text).find()) {
            return true;
        }
        return EVERYDAY_WORDS.contains(signal.canonical())
                ? writtenAsAName(text, signal.canonical())
                : ResumeSkillCatalog.containsPhrase(text, wanted) || ResumeSkillCatalog.containsPhrase(text, signal.canonical());
    }

    /** The name as a whole word, capitalized as written. */
    private static boolean writtenAsAName(String text, String name) {
        return Pattern.compile("(^|[^A-Za-z0-9])" + Pattern.quote(name) + "(?=$|[^A-Za-z0-9])").matcher(text).find();
    }
}
