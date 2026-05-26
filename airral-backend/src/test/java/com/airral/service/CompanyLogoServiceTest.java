package com.airral.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompanyLogoServiceTest {

    @Test
    void returnsBrandfetchLogoUrlWhenClientIdAndDomainExist() {
        CompanyLogoService service = new CompanyLogoService("client-123");

        assertThat(service.logoUrl("https://www.Example.com/jobs", "https://fallback.example/logo.png"))
                .isEqualTo("https://cdn.brandfetch.io/example.com/h/128/w/128/icon.png?c=client-123");
    }

    @Test
    void fallsBackToSafeStoredLogoWhenBrandfetchIsNotConfigured() {
        CompanyLogoService service = new CompanyLogoService("");

        assertThat(service.logoUrl("example.com", "https://assets.example/logo.png"))
                .isEqualTo("https://assets.example/logo.png");
    }

    @Test
    void rejectsUnsafeFallbackLogoUrls() {
        CompanyLogoService service = new CompanyLogoService("");

        assertThat(service.logoUrl("example.com", "javascript:alert(1)")).isNull();
    }

    @Test
    void normalizesCompanyDomains() {
        CompanyLogoService service = new CompanyLogoService("");

        assertThat(service.normalizeDomain("WWW.AIRRAL.COM/careers")).isEqualTo("airral.com");
    }
}
