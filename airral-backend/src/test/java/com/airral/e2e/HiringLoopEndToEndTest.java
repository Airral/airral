package com.airral.e2e;

import com.airral.service.CompanyVerificationService;
import com.airral.service.FirebaseEmailLinkSender;
import com.airral.service.FirebaseIdentityService;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The whole hiring loop, through the API, against a real Postgres: a company
 * signs up and is reviewed, invites a hiring manager, posts a job; an applicant
 * signs up, uploads a resume and applies; the team interviews and scores her,
 * sends an offer, she accepts, and the job is closed out.
 *
 * <p>Everything runs as it does in production except the two things that
 * leave AIRRAL: Firebase, which proves email addresses by link, and outgoing
 * email. The Firebase token checks are stubbed to name the address each link
 * was sent to, and email delivery is switched off.
 *
 * <p>It needs a database of its own, because the application migrates it on
 * startup: set AIRRAL_E2E_DATABASE (and AIRRAL_E2E_DB_USER,
 * AIRRAL_E2E_DB_PASSWORD, AIRRAL_E2E_DB_HOST if not local defaults). Without
 * it the test is skipped, so the unit suite runs anywhere.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "airral.jobs.sync.enabled=false",
        "airral.notifications.scheduler.enabled=false",
        "airral.notifications.email.enabled=false",
        "airral.auth.bootstrap-admin-email=",
        "airral.alerts.slack-webhook-url=",
        "jwt.encryption-secret=end-to-end-test-secret-that-is-comfortably-longer-than-32-bytes",
})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "AIRRAL_E2E_DATABASE", matches = ".+")
class HiringLoopEndToEndTest {

    private static final String PASSWORD = "Passw0rd-e2e";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws IOException {
        String host = env("AIRRAL_E2E_DB_HOST", "localhost");
        String database = System.getenv("AIRRAL_E2E_DATABASE");
        String user = env("AIRRAL_E2E_DB_USER", "postgres");
        String password = env("AIRRAL_E2E_DB_PASSWORD", "");
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + host + ":5432/" + database);
        registry.add("spring.r2dbc.username", () -> user);
        registry.add("spring.r2dbc.password", () -> password);
        registry.add("spring.flyway.url", () -> "jdbc:postgresql://" + host + ":5432/" + database);
        registry.add("spring.flyway.user", () -> user);
        registry.add("spring.flyway.password", () -> password);
        String uploads = Files.createTempDirectory("airral-e2e-uploads").toString();
        registry.add("file.upload.storage-path", () -> uploads);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Autowired
    private WebTestClient web;

    @Autowired
    private CompanyVerificationService companyReview;

    @MockBean
    private FirebaseIdentityService firebase;

    @MockBean
    private FirebaseEmailLinkSender links;

    /** Addresses unique to this run, so a database can be reused. */
    private final String run = UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void setUp() {
        web = web.mutate().responseTimeout(Duration.ofSeconds(30)).build();
        when(links.send(any(), any())).thenReturn(Mono.empty());
        when(links.sendInvitation(anyLong(), any(), any())).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("a company signs up, adds a hiring manager, posts a job, and hires an applicant")
    void aCompanyHiresSomeoneThroughAirral() throws IOException {
        // A company domain of its own each run: a verified domain is locked to its company.
        String companyDomain = "acme-" + run + ".test";
        String hanaEmail = "hana@" + companyDomain;
        String miaEmail = "mia@" + companyDomain;
        String amyEmail = "amy-" + run + "@example-e2e.test";

        // 1. An HR manager signs up, which creates the company. It waits for review.
        JsonNode hana = call(HttpMethod.POST, "/api/auth/register", null, Map.of(
                "email", hanaEmail, "password", PASSWORD, "firstName", "Hana", "lastName", "Hill",
                "companyName", "Acme E2E " + run), HttpStatus.CREATED);
        String hr = hana.get("token").asText();
        long companyId = hana.get("organizationId").asLong();
        proveAddress(hanaEmail);
        assertThat(call(HttpMethod.GET, "/api/auth/me", hr, null, HttpStatus.OK)
                .get("organizationVerificationStatus").asText()).isEqualTo("PENDING");

        // 2. AIRRAL reviews and approves the company (the admin portal's approve).
        companyReview.approve(companyId, "End-to-end test").block();

        // 3. HR fills in the company profile.
        call(HttpMethod.PUT, "/api/company", hr, Map.of(
                "industry", "Retail", "companySizeRange", "11-50", "website", "https://" + companyDomain,
                "timezone", "America/New_York"), HttpStatus.OK);

        // 4. HR invites a hiring manager, who accepts from the email's link and signs in.
        call(HttpMethod.POST, "/api/users/invite", hr, Map.of(
                "email", miaEmail, "role", "MANAGER", "firstName", "Mia", "lastName", "Moss"), HttpStatus.CREATED);
        ArgumentCaptor<String> invitationToken = ArgumentCaptor.forClass(String.class);
        verify(links).sendInvitation(anyLong(), eq(miaEmail), invitationToken.capture());
        stubFirebaseFor(miaEmail, "mia-link");
        call(HttpMethod.POST, "/api/auth/invitations/" + invitationToken.getValue() + "/accept", null, Map.of(
                "idToken", "mia-link", "password", PASSWORD), HttpStatus.CREATED);
        JsonNode mia = call(HttpMethod.POST, "/api/auth/login", null,
                Map.of("email", miaEmail, "password", PASSWORD), HttpStatus.OK);
        String manager = mia.get("token").asText();
        long miaId = mia.get("userId").asLong();
        assertThat(mia.get("role").asText()).isEqualTo("MANAGER");
        assertThat(mia.get("organizationId").asLong()).isEqualTo(companyId);

        // 5. HR posts a job, with Mia as its hiring manager.
        JsonNode job = call(HttpMethod.POST, "/api/jobs", hr, Map.of(
                "title", "Store manager", "description", "Run a busy hardware store.",
                "location", "Atlanta, GA", "status", "OPEN", "hiringManagerId", miaId,
                "atsKeywords", List.of("Inventory Management", "Forklift")), HttpStatus.CREATED);
        long jobId = job.get("id").asLong();

        // 6. An applicant signs up, proves her address, uploads a resume and applies in AIRRAL.
        JsonNode amy = call(HttpMethod.POST, "/api/auth/register", null, Map.of(
                "email", amyEmail, "password", PASSWORD, "firstName", "Amy", "lastName", "Adams"), HttpStatus.CREATED);
        String applicant = amy.get("token").asText();
        long amyId = amy.get("userId").asLong();
        proveAddress(amyEmail);
        uploadResume(applicant);
        JsonNode application = call(HttpMethod.POST, "/api/applications", applicant, Map.of(
                "jobId", jobId, "applicantName", "Amy Adams", "applicantEmail", amyEmail), HttpStatus.CREATED);
        long applicationId = application.get("id").asLong();
        assertThat(application.get("resumeOnFile").asBoolean()).isTrue();
        // Her copy leaves out the company's evidence.
        assertThat(application.get("atsMatchedKeywords").isNull()).isTrue();
        // One application per job.
        call(HttpMethod.POST, "/api/applications", applicant, Map.of(
                "jobId", jobId, "applicantName", "Amy Adams", "applicantEmail", amyEmail), HttpStatus.CONFLICT);

        // 7. The hiring manager sees her, with the job's keywords read against the PDF she uploaded,
        //    and HR opens her resume.
        JsonNode managerView = call(HttpMethod.GET, "/api/applications", manager, null, HttpStatus.OK);
        assertThat(ids(managerView)).contains(applicationId);
        JsonNode evidence = StreamSupport.stream(managerView.spliterator(), false)
                .filter(node -> node.get("id").asLong() == applicationId).findFirst().orElseThrow();
        assertThat(evidence.get("atsMatchedKeywords").get(0).asText()).isEqualTo("Inventory Management");
        assertThat(evidence.get("atsMissingKeywords").get(0).asText()).isEqualTo("Forklift");
        assertThat(evidence.get("alignmentSource").asText()).isEqualTo("RESUME_AND_NOTE");
        byte[] resume = web.get().uri("/api/applications/" + applicationId + "/resume")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + hr)
                .exchange().expectStatus().isOk()
                .expectBody(byte[].class).returnResult().getResponseBody();
        assertThat(new String(resume, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        // An applicant has no business in the company's pipeline.
        web.get().uri("/api/applications").header(HttpHeaders.AUTHORIZATION, "Bearer " + applicant)
                .exchange().expectStatus().isForbidden();

        // 8. HR reviews and shortlists her, and books an interview with Mia on it.
        call(HttpMethod.PUT, "/api/applications/" + applicationId + "/status?status=UNDER_REVIEW", hr, Map.of(), HttpStatus.OK);
        call(HttpMethod.PUT, "/api/applications/" + applicationId + "/status?status=SHORTLISTED", hr, Map.of(), HttpStatus.OK);
        JsonNode interview = call(HttpMethod.POST, "/api/interviews", hr, Map.of(
                "applicationId", applicationId, "interviewDate", "2026-10-06T14:00:00", "timeZone", "America/New_York",
                "durationMinutes", 45, "interviewerIds", List.of(miaId),
                "notifyCandidate", true, "notifyInterviewers", true), HttpStatus.CREATED);
        long interviewId = interview.get("id").asLong();
        assertThat(interview.get("interviewers").get(0).get("id").asLong()).isEqualTo(miaId);

        // 9. Mia finds it in My interviews and submits her scorecard; HR reads it.
        assertThat(ids(call(HttpMethod.GET, "/api/interviews/mine", manager, null, HttpStatus.OK))).contains(interviewId);
        JsonNode blank = call(HttpMethod.GET, "/api/interviews/" + interviewId + "/scorecard", manager, null, HttpStatus.OK);
        List<Map<String, Object>> ratings = new ArrayList<>();
        blank.get("ratings").forEach(rating -> ratings.add(Map.of("criterion", rating.get("criterion").asText(), "rating", 4)));
        assertThat(ratings).isNotEmpty();
        Map<String, Object> scorecard = new HashMap<>();
        scorecard.put("ratings", ratings);
        scorecard.put("overallNotes", "Calm under pressure.");
        scorecard.put("recommendation", "HIRE");
        scorecard.put("submit", true);
        assertThat(call(HttpMethod.PUT, "/api/interviews/" + interviewId + "/scorecard", manager, scorecard, HttpStatus.OK)
                .get("status").asText()).isEqualTo("SUBMITTED");
        JsonNode cards = call(HttpMethod.GET, "/api/applications/" + applicationId + "/scorecards", hr, null, HttpStatus.OK);
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).get("recommendation").asText()).isEqualTo("HIRE");

        // 10. HR sends an offer. Only Amy can accept it, and she does.
        JsonNode offer = call(HttpMethod.POST, "/api/offers", hr, Map.of(
                "applicationId", applicationId, "salary", 72000, "currency", "USD"), HttpStatus.CREATED);
        long offerId = offer.get("id").asLong();
        assertThat(call(HttpMethod.POST, "/api/offers/" + offerId + "/send", hr, Map.of("expiresInDays", 14), HttpStatus.OK)
                .get("status").asText()).isEqualTo("SENT");
        call(HttpMethod.POST, "/api/offers/" + offerId + "/accept", hr, Map.of(), HttpStatus.CONFLICT);
        assertThat(ids(call(HttpMethod.GET, "/api/offers/mine", applicant, null, HttpStatus.OK))).containsExactly(offerId);
        assertThat(call(HttpMethod.POST, "/api/offers/" + offerId + "/accept", applicant, Map.of(), HttpStatus.OK)
                .get("status").asText()).isEqualTo("ACCEPTED");

        // 11. Amy's tracker says she is hired, and HR closes out the job.
        JsonNode mine = call(HttpMethod.GET, "/api/applications/applicant/" + amyId, applicant, null, HttpStatus.OK);
        assertThat(mine.get(0).get("stage").asText()).isEqualTo("HIRED");
        assertThat(call(HttpMethod.POST, "/api/jobs/" + jobId + "/close-out", hr, Map.of(
                "markFilled", true, "turnDownOthers", true), HttpStatus.OK).get("markedFilled").asBoolean()).isTrue();
        assertThat(call(HttpMethod.GET, "/api/jobs/" + jobId, hr, null, HttpStatus.OK).get("status").asText())
                .isEqualTo("FILLED");
    }

    @Test
    @DisplayName("HR edits a teammate, moves them out of hiring, and switches their account off")
    void hrManagesTheTeam() {
        String companyDomain = "globex-" + run + ".test";
        String hanaEmail = "hana@" + companyDomain;
        String miaEmail = "mia@" + companyDomain;

        JsonNode hana = call(HttpMethod.POST, "/api/auth/register", null, Map.of(
                "email", hanaEmail, "password", PASSWORD, "firstName", "Hana", "lastName", "Hill",
                "companyName", "Globex E2E " + run), HttpStatus.CREATED);
        String hr = hana.get("token").asText();
        long hanaId = hana.get("userId").asLong();
        proveAddress(hanaEmail);
        companyReview.approve(hana.get("organizationId").asLong(), "End-to-end test").block();

        call(HttpMethod.POST, "/api/users/invite", hr, Map.of("email", miaEmail, "role", "MANAGER"), HttpStatus.CREATED);
        // A second invitation to an address with one still open is refused, not duplicated.
        call(HttpMethod.POST, "/api/users/invite", hr, Map.of("email", miaEmail, "role", "MANAGER"), HttpStatus.CONFLICT);
        ArgumentCaptor<String> invitationToken = ArgumentCaptor.forClass(String.class);
        verify(links).sendInvitation(anyLong(), eq(miaEmail), invitationToken.capture());
        stubFirebaseFor(miaEmail, "mia-team-link");
        call(HttpMethod.POST, "/api/auth/invitations/" + invitationToken.getValue() + "/accept", null, Map.of(
                "idToken", "mia-team-link", "password", PASSWORD), HttpStatus.CREATED);
        JsonNode mia = call(HttpMethod.POST, "/api/auth/login", null,
                Map.of("email", miaEmail, "password", PASSWORD), HttpStatus.OK);
        long miaId = mia.get("userId").asLong();
        long jobId = call(HttpMethod.POST, "/api/jobs", hr, Map.of(
                "title", "Buyer", "description", "Buy for the stores.", "status", "OPEN",
                "hiringManagerId", miaId), HttpStatus.CREATED).get("id").asLong();

        // A profile edit writes the profile only.
        assertThat(call(HttpMethod.PUT, "/api/users/" + miaId, hr, Map.of("jobTitle", "Senior buyer"), HttpStatus.OK)
                .get("jobTitle").asText()).isEqualTo("Senior buyer");

        // Moved out of hiring: signed out, and no longer the job's hiring manager.
        assertThat(call(HttpMethod.PUT, "/api/users/" + miaId + "/role", hr, Map.of("role", "EMPLOYEE"), HttpStatus.OK)
                .get("role").asText()).isEqualTo("EMPLOYEE");
        call(HttpMethod.GET, "/api/auth/me", mia.get("token").asText(), null, HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.GET, "/api/jobs/" + jobId, hr, null, HttpStatus.OK).path("hiringManagerId").asLong(0))
                .isZero();

        // Switched off: the password no longer signs in.
        assertThat(call(HttpMethod.PUT, "/api/users/" + miaId + "/active", hr, Map.of("active", false), HttpStatus.OK)
                .get("isActive").asBoolean()).isFalse();
        call(HttpMethod.POST, "/api/auth/login", null, Map.of("email", miaEmail, "password", PASSWORD),
                HttpStatus.UNAUTHORIZED);

        // Nobody changes their own role, so the company keeps its HR manager.
        call(HttpMethod.PUT, "/api/users/" + hanaId + "/role", hr, Map.of("role", "EMPLOYEE"), HttpStatus.BAD_REQUEST);
    }

    /** Follow the verification link Firebase would have emailed. */
    private void proveAddress(String email) {
        String link = "link-" + email;
        stubFirebaseFor(email, link);
        assertThat(call(HttpMethod.POST, "/api/auth/verify-email", null, Map.of("idToken", link), HttpStatus.OK)
                .get("verified").asBoolean()).isTrue();
    }

    private void stubFirebaseFor(String email, String idToken) {
        when(firebase.verifyIdToken(idToken)).thenReturn(Mono.just(
                new FirebaseIdentityService.VerifiedEmail(email, "uid-" + email, Instant.now())));
    }

    private void uploadResume(String token) throws IOException {
        byte[] pdf = resumePdf();
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", new ByteArrayResource(pdf) {
            @Override
            public String getFilename() {
                return "amy-adams-resume.pdf";
            }
        }).contentType(MediaType.APPLICATION_PDF);
        web.post().uri("/api/candidate/profile/resume")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body.build()))
                .exchange()
                .expectStatus().isOk();
    }

    private static byte[] resumePdf() throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            // Enough words that the resume parser keeps its text (it wants at least 20).
            String[] lines = {
                    "Amy Adams",
                    "Store manager, Atlanta, GA",
                    "Experience",
                    "Store manager, Hillside Hardware, 2020 to now: ran a store of 40 people,",
                    "owned inventory management and weekly scheduling, and cut stock losses by 18 percent.",
                    "Assistant manager, Corner Market, 2017 to 2020: trained new staff and handled deliveries.",
            };
            try (PDPageContentStream text = new PDPageContentStream(document, page)) {
                text.beginText();
                text.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                text.setLeading(16);
                text.newLineAtOffset(72, 700);
                for (String line : lines) {
                    text.showText(line);
                    text.newLine();
                }
                text.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private JsonNode call(HttpMethod method, String uri, String token, Object body, HttpStatus expected) {
        WebTestClient.RequestBodySpec request = web.method(method).uri(uri);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        WebTestClient.ResponseSpec response = body == null
                ? request.exchange()
                : request.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
        return response.expectStatus().isEqualTo(expected)
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
    }

    private static List<Long> ids(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(node -> node.get("id").asLong()).toList();
    }
}
