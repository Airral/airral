package com.airral.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.IDN;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Service
public class CompanyLogoService {

    private static final String BRANDFETCH_LOGO_URL = "https://cdn.brandfetch.io/%s/h/128/w/128/icon.png?c=%s";

    private final String brandfetchClientId;

    public CompanyLogoService(@Value("${airral.company-logos.brandfetch-client-id:}") String brandfetchClientId) {
        this.brandfetchClientId = brandfetchClientId == null ? "" : brandfetchClientId.trim();
    }

    public String logoUrl(String companyDomain, String fallbackLogoUrl) {
        String normalizedDomain = normalizeDomain(companyDomain);
        if (normalizedDomain != null && !brandfetchClientId.isBlank()) {
            return BRANDFETCH_LOGO_URL.formatted(
                    normalizedDomain,
                    URLEncoder.encode(brandfetchClientId, StandardCharsets.UTF_8)
            );
        }

        return safeHttpUrl(fallbackLogoUrl);
    }

    public String normalizeDomain(String rawDomain) {
        if (rawDomain == null || rawDomain.isBlank()) {
            return null;
        }

        String value = rawDomain.trim();
        if (!value.contains("://")) {
            value = "https://" + value;
        }

        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return null;
            }

            String normalizedHost = host.toLowerCase(Locale.US);
            if (normalizedHost.startsWith("www.")) {
                normalizedHost = normalizedHost.substring(4);
            }

            return IDN.toASCII(normalizedHost);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String safeHttpUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }

        try {
            URI uri = URI.create(rawUrl.trim());
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return null;
            }
            return uri.toString();
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
