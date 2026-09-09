package com.airral.service;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;

import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * What counts as an email address on the capture form.
 *
 * <p>The whole check was that the string contained an '@'. "audit-probe@", "@"
 * and "a@b" all became subscribers, so the capture list accumulated rows nobody
 * can ever send to -- and being sendable is the only thing that list is for.
 *
 * <p>Both directions are pinned, and the accept list is deliberately the longer
 * one. No check short of actually sending mail separates a real address from a
 * well-formed one, and refusing someone's real address loses a person who wanted
 * to hear from us, so anything that is merely unusual has to survive.
 */
class VisitorSignalValidationTest {

    private final VisitorSignalService service =
            new VisitorSignalService(mock(DatabaseClient.class), "test-salt");

    @Test
    @DisplayName("an accepted address is stored trimmed and lower-cased")
    void normalisesBeforeStoring() {
        assertThat(VisitorSignalService.normalizeEmail("  Ada.Lovelace@Example.COM  "))
                .isEqualTo("ada.lovelace@example.com");
        assertThat(VisitorSignalService.normalizeEmail(null)).isNull();
    }

    @Test
    @DisplayName("plus-addressing, subdomains and unusual TLDs are still accepted")
    void acceptsAddressesThatOnlyLookOdd() {
        List<String> accepted = List.of(
                "ada@example.com",
                // One person, many senders. Common enough among this audience
                // that rejecting it would cost real signups.
                "ada+jobs@example.com",
                "ada@mail.example.co.uk",
                "ada.b-c_d@example.io",
                // About as short as a deliverable address gets.
                "a@b.co",
                "ada@example.engineering",
                // An internationalised domain, which arrives already punycoded.
                "ada@sub.domain.xn--p1ai");

        for (String address : accepted) {
            assertThat(VisitorSignalService.normalizeEmail(address))
                    .as("%s must still be accepted", address)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("the junk that was reaching the table is refused")
    void rejectsAddressesNothingCouldBeSentTo() {
        List<String> rejected = List.of(
                // The three that were observed stored as subscribers.
                "audit-probe@",
                "@",
                "a@b",
                "@example.com",
                "ada@example.",
                "ada@.com",
                // The two-dot form slipped through a lastIndexOf-only leading
                // dot check, so it is pinned separately from "ada@.com".
                "ada@.b.com",
                "ada@exa..mple.com",
                // No TLD is one character, and no TLD outside punycode has a
                // digit in it.
                "ada@example.c",
                "ada@example.c0m",
                "ada example.com",
                "ada@@example.com",
                "ada @example.com",
                "");

        for (String address : rejected) {
            assertThat(VisitorSignalService.normalizeEmail(address))
                    .as("%s must be refused", address)
                    .isNull();
        }
    }

    @Test
    @DisplayName("a refusal is an IllegalArgumentException, which is the 400")
    void refusalKeepsTheFourHundred() {
        // The controller maps exactly this exception to 400. Anything else falls
        // through to the generic handler, and someone typing a typo into a
        // signup box is told the server broke.
        StepVerifier.create(service.captureEmail("audit-probe@", "website", null))
                .verifyError(IllegalArgumentException.class);
    }
}
