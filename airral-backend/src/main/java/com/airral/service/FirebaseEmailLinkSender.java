package com.airral.service;

import com.airral.domain.User;
import com.airral.domain.enums.UserRole;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Asks Firebase to email a one-time link, from the server.
 *
 * <p>The links used to be requested by the browser, straight from the portal,
 * which meant Firebase mailed whatever address anyone typed -- including
 * addresses that have never had an AIRRAL account. That is exactly the traffic
 * spam filters punish, and it let anyone use AIRRAL's pages to fill a stranger's
 * inbox. The server now decides first: a reset link goes only to an address with
 * an active account, a verification link only to the signed-in person's own.
 *
 * <p>Calls Identity Toolkit's accounts:sendOobCode as the API's own service
 * account (roles/firebaseauth.admin), without returnOobLink, so Firebase sends
 * the mail itself. The link lands on the portal the account belongs to.
 *
 * <p>With {@code airral.auth.email-link.deliver=false} -- the local profile --
 * nothing is mailed: Firebase returns the link instead, and it is logged. Local
 * runs and the verification gate sign up throwaway addresses, some on real
 * domains, and must never email them.
 */
@Service
public class FirebaseEmailLinkSender {

    private static final Logger log = LoggerFactory.getLogger(FirebaseEmailLinkSender.class);
    private static final String SCOPE = "https://www.googleapis.com/auth/cloud-platform";

    public enum Purpose {
        VERIFY("/verify-email"),
        RESET("/reset-password");

        final String path;

        Purpose(String path) {
            this.path = path;
        }
    }

    private final WebClient webClient;
    private final String projectId;
    private final String applicantUrl;
    private final String hrUrl;
    private final boolean localGcloudToken;
    private final boolean deliver;
    private volatile GoogleCredentials credentials;

    public FirebaseEmailLinkSender(
            WebClient.Builder webClientBuilder,
            @Value("${airral.auth.firebase.project-id:}") String projectId,
            @Value("${airral.auth.email-link.applicant-url:https://apply.airral.com}") String applicantUrl,
            @Value("${airral.auth.email-link.hr-url:https://app.airral.com}") String hrUrl,
            @Value("${airral.auth.email-link.local-gcloud-token:false}") boolean localGcloudToken,
            @Value("${airral.auth.email-link.deliver:true}") boolean deliver) {
        this.webClient = webClientBuilder.baseUrl("https://identitytoolkit.googleapis.com").build();
        this.projectId = projectId == null ? "" : projectId.trim();
        this.applicantUrl = trimSlash(applicantUrl);
        this.hrUrl = trimSlash(hrUrl);
        this.localGcloudToken = localGcloudToken;
        this.deliver = deliver;
    }

    /** Where the link lands: the portal this account signs in to. */
    String continueUrl(User user, Purpose purpose) {
        boolean applicant = user.getRole() == null || user.getRole() == UserRole.APPLICANT;
        return (applicant ? applicantUrl : hrUrl) + purpose.path;
    }

    public Mono<Void> send(User user, Purpose purpose) {
        if (!StringUtils.hasText(projectId)) {
            return Mono.error(new IllegalStateException("airral.auth.firebase.project-id is not set"));
        }
        String continueUrl = continueUrl(user, purpose);
        return accessToken()
                .flatMap(token -> webClient.post()
                        .uri("/v1/projects/{project}/accounts:sendOobCode", projectId)
                        .header("Authorization", "Bearer " + token)
                        // Needed when the caller is a user credential rather than
                        // this project's service account; harmless otherwise.
                        .header("x-goog-user-project", projectId)
                        .bodyValue(Map.of(
                                "requestType", "EMAIL_SIGNIN",
                                "email", user.getEmail(),
                                "continueUrl", continueUrl,
                                "canHandleCodeInApp", true,
                                "returnOobLink", !deliver))
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(body -> new IllegalStateException("Firebase refused to send the "
                                        + purpose.name().toLowerCase() + " link (HTTP "
                                        + response.statusCode().value() + "): " + body)))
                        .bodyToMono(Map.class)
                        .defaultIfEmpty(Map.of()))
                .timeout(Duration.ofSeconds(15))
                .doOnNext(response -> {
                    if (deliver) {
                        log.info("Sent {} link to user {} (lands on {})",
                                purpose.name().toLowerCase(), user.getId(), continueUrl);
                    } else {
                        log.info("Email delivery is off; {} link for user {}: {}",
                                purpose.name().toLowerCase(), user.getId(), response.get("oobLink"));
                    }
                })
                .then();
    }

    /**
     * An OAuth token for the API's own identity. On Cloud Run that is the
     * attached service account, through application default credentials. Local
     * development has no such credentials on most machines, so the local profile
     * can instead borrow the developer's gcloud login -- off by default and never
     * set outside application-local.yml.
     */
    private Mono<String> accessToken() {
        return Mono.fromCallable(() -> {
                    try {
                        GoogleCredentials creds = credentials;
                        if (creds == null) {
                            creds = GoogleCredentials.getApplicationDefault();
                            if (creds.createScopedRequired()) {
                                creds = creds.createScoped(SCOPE);
                            }
                            credentials = creds;
                        }
                        creds.refreshIfExpired();
                        AccessToken token = creds.getAccessToken();
                        if (token != null && StringUtils.hasText(token.getTokenValue())) {
                            return token.getTokenValue();
                        }
                    } catch (Exception adcUnavailable) {
                        if (!localGcloudToken) {
                            throw adcUnavailable;
                        }
                    }
                    if (localGcloudToken) {
                        return gcloudToken();
                    }
                    throw new IllegalStateException("No Google credentials available to send email links");
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static String gcloudToken() throws Exception {
        Process process = new ProcessBuilder("gcloud", "auth", "print-access-token").redirectErrorStream(false).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("gcloud auth print-access-token timed out");
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String token = reader.readLine();
            if (!StringUtils.hasText(token)) {
                throw new IllegalStateException("gcloud returned no access token; run gcloud auth login");
            }
            return token.trim();
        }
    }

    private static String trimSlash(String url) {
        String value = url == null ? "" : url.trim();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
