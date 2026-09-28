package com.airral.service;

import org.springframework.web.util.HtmlUtils;

/**
 * Pieces of the HTML in AIRRAL's emails. Everything a person typed goes
 * through {@link #escape}, so a company name or job title cannot add markup.
 */
final class EmailHtml {

    private EmailHtml() {
    }

    /** "Hi Amy," from a full name, or "Hello," without one. */
    static String greeting(String fullName) {
        String name = fullName == null ? "" : fullName.trim();
        String first = name.isEmpty() ? "" : name.split("\\s+")[0];
        return paragraph(first.isEmpty() ? "Hello," : "Hi " + escape(first) + ",");
    }

    static String paragraph(String html) {
        return "<p style=\"margin:0 0 16px; font-size:15px; line-height:1.6; color:#111827;\">" + html + "</p>";
    }

    /** Typed text kept as the person wrote it, line breaks included. */
    static String block(String text) {
        return "<p style=\"margin:0 0 16px; font-size:14px; line-height:1.6; color:#374151; white-space:pre-line;\">"
                + escape(text) + "</p>";
    }

    static String strong(String text) {
        return "<strong>" + escape(text) + "</strong>";
    }

    static String button(String url, String label) {
        return "<p style=\"margin:24px 0 0;\"><a href=\"" + escape(url) + "\" style=\"display:inline-block;"
                + " background:#007C6D; color:#ffffff; text-decoration:none; font-weight:600;"
                + " padding:10px 18px; border-radius:6px;\">" + escape(label) + "</a></p>";
    }

    static String escape(String text) {
        return text == null ? "" : HtmlUtils.htmlEscape(text);
    }
}
