package com.airral.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.adapter.ForwardedHeaderTransformer;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;

import com.airral.config.ClientIpConfig.ClientIpStampingTransformer;
import com.airral.controller.AuthController;
import com.airral.controller.VisitorSignalController;
import com.airral.dto.request.RegisterRequest;
import com.airral.dto.response.AuthResponse;
import com.airral.security.JwtTokenProvider;
import com.airral.security.LoginThrottle;
import com.airral.security.TokenVersionCache;
import com.airral.service.AuthService;
import com.airral.service.VisitorSignalService;

import reactor.core.publisher.Mono;

/**
 * The bug these pin was not that the address was parsed wrongly -- it parsed
 * exactly what it meant to. It was that the thing it parsed belonged to the
 * attacker. Cloud Run appends the real client address to a caller-supplied
 * X-Forwarded-For rather than replacing the header, so reading the left-most
 * entry read the caller's own text, and rotating it bought a fresh rate-limit
 * bucket on every request. /api/auth/register and /api/auth/google check only
 * the per-address bucket, and that bucket is the only limit that spans
 * accounts, so enumeration and spraying had no ceiling at all.
 *
 * <p>A test that fed one address in and got one address out would have passed
 * throughout. What has to be held still is which END of the chain is believed.
 */
class ClientIpConfigTest {

    private static final String REAL = "198.51.100.7";

    /** Runs a request through the transformer and returns what it stamped. */
    private static String stamp(int trustedHopCount, MockServerHttpRequest request) {
        ServerHttpRequest transformed = new ClientIpStampingTransformer(trustedHopCount).apply(request);
        return transformed.getHeaders().getFirst(ClientIpConfig.CLIENT_IP_HEADER);
    }

    private static MockServerHttpRequest.BodyBuilder post() {
        return MockServerHttpRequest.post("http://api.airral.com/api/auth/register");
    }

    @Test
    @DisplayName("a spoofed left-most entry is ignored and the appended real address is used")
    void leftMostEntryIsNotBelieved() {
        // Exactly what Cloud Run produces when a caller sends its own header:
        // the caller's value, then the address Google accepted the connection
        // from, appended on the right.
        assertEquals(REAL, stamp(1, post().header(ClientIpConfig.FORWARDED_FOR, "203.0.113.9, " + REAL).build()));
    }

    @Test
    @DisplayName("a long chain of spoofed entries still resolves to the same appended address")
    void aWholeForgedChainChangesNothing() {
        // The whole attack was that the key moved when this text moved. It must
        // not, however much of it there is.
        String forged = "203.0.113.9, 203.0.113.10, 10.0.0.1, unknown, 2001:db8::1";

        assertEquals(REAL, stamp(1, post().header(ClientIpConfig.FORWARDED_FOR, forged + ", " + REAL).build()));
        assertEquals(REAL, stamp(1, post()
                .header(ClientIpConfig.FORWARDED_FOR, "192.0.2.44, " + REAL)
                .build()),
                "a different forged prefix must produce the same bucket, not a new one");
    }

    @Test
    @DisplayName("several X-Forwarded-For lines are one chain, read from the right of all of them")
    void repeatedHeaderLinesCannotHideAChain() {
        // Reading only the first header line would let a caller supply an entire
        // chain of their own, right-hand end included -- a simpler bypass than
        // the one being fixed. RFC 7230 makes repeated lines one comma-joined
        // value in order, and that is what gets read.
        MockServerHttpRequest request = post()
                .header(ClientIpConfig.FORWARDED_FOR, "203.0.113.9, 203.0.113.10")
                .header(ClientIpConfig.FORWARDED_FOR, REAL)
                .build();

        assertEquals(REAL, stamp(1, request));
    }

    @Test
    @DisplayName("a quote in the chain cannot glue the appended address onto caller text")
    void quotingCannotMergeTheAppendedEntry() {
        // Found in review, and the reason this class does its own splitting.
        // HttpHeaders.getValuesAsList honours RFC 7230 quoted strings, and
        // X-Forwarded-For has no quoting -- so one unbalanced double quote made
        // it swallow the comma Cloud Run joins its appended address with, and
        // the whole header came back as a single entry. Measured on spring-web
        // 6.1.6: getValuesAsList("\"evil, 198.51.100.7") returns exactly
        // ["evil, 198.51.100.7"], so the "right-most" entry was caller text
        // again and rotating it bought a fresh bucket per request, which is the
        // original bug with one extra character.
        assertEquals(REAL, stamp(1, post()
                .header(ClientIpConfig.FORWARDED_FOR, "\"evil-bucket-42, " + REAL)
                .build()));
        assertEquals(REAL, stamp(1, post()
                .header(ClientIpConfig.FORWARDED_FOR, "\"evil-bucket-43, " + REAL)
                .build()),
                "a different forged prefix must not produce a different bucket");
        assertEquals(REAL, stamp(1, post()
                .header(ClientIpConfig.FORWARDED_FOR, "\"a\\, " + REAL)
                .build()),
                "a backslash escape inside the quote must not swallow the separator either");

        // A quoted comma merges two entries instead of adding one, which moves
        // every index counted from the right. Harmless at hop count 1, but it
        // would hand the caller the key at the hop count a load balancer needs.
        assertEquals(REAL, stamp(2, post()
                .header(ClientIpConfig.FORWARDED_FOR, "\"203.0.113.9,203.0.113.10\", " + REAL + ", 10.128.0.5")
                .build()));
    }

    @Test
    @DisplayName("no forwarded header at all falls back to the socket peer")
    void noHeaderFallsBackToTheRemoteAddress() {
        // Local development sends no X-Forwarded-For and must keep working.
        assertEquals("127.0.0.1", stamp(1, post()
                .remoteAddress(new InetSocketAddress("127.0.0.1", 51234))
                .build()));
    }

    @Test
    @DisplayName("a blank or separator-only header does not throw and falls through")
    void blankHeaderIsTolerated() {
        assertEquals("127.0.0.1", stamp(1, post()
                .header(ClientIpConfig.FORWARDED_FOR, "")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 51234))
                .build()));
        assertEquals("127.0.0.1", stamp(1, post()
                .header(ClientIpConfig.FORWARDED_FOR, "   ")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 51234))
                .build()));

        // Separators with nothing between them, read through the accessor
        // rather than the transformer: Spring's own parse rejects that shape
        // with a 400 before a handler is ever reached, which is the behaviour
        // this deployment already had. What is asserted here is that AIRRAL's
        // resolution does not add a second way to fail.
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/events")
                .header(ClientIpConfig.FORWARDED_FOR, " , , ")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 51234))
                .build());
        assertEquals("127.0.0.1", ClientIpConfig.clientAddress(exchange));
    }

    @Test
    @DisplayName("an entry that is not an address is passed through rather than rejected")
    void malformedEntryIsNotFatal() {
        // The value is a bucket key, never a decision. Refusing the request
        // would turn a proxy quirk into an outage.
        assertEquals("not-an-address", stamp(1, post()
                .header(ClientIpConfig.FORWARDED_FOR, "203.0.113.9, not-an-address")
                .build()));
    }

    @Test
    @DisplayName("an unresolved remote address does not NPE, and nothing yields \"unknown\"")
    void remoteAddressFallbacksArePreserved() {
        // getAddress() returning null on an unresolved address is what turned
        // every sign-in into a 500 once already. The host string still names
        // the peer.
        assertEquals(REAL, stamp(1, post()
                .remoteAddress(InetSocketAddress.createUnresolved(REAL, 443))
                .build()));

        assertEquals("unknown", stamp(1, post().build()),
                "no address must share a bucket, never fail the request");
    }

    @Test
    @DisplayName("an inbound copy of the internal header cannot influence the result")
    void inboundStampIsOverwritten() {
        // The header is only safe as a vehicle because the transformer sets it
        // on every request, before the exchange and so before any filter or
        // handler exists. If an inbound copy survived, this would be an easier
        // bypass than the one being fixed.
        assertEquals(REAL, stamp(1, post()
                .header(ClientIpConfig.CLIENT_IP_HEADER, "203.0.113.9")
                .header(ClientIpConfig.FORWARDED_FOR, "203.0.113.9, " + REAL)
                .build()));

        // Also on the path with no chain to resolve from, where there is no
        // forwarded header to trigger any of the framework's own rewriting.
        assertEquals("127.0.0.1", stamp(1, post()
                .header(ClientIpConfig.CLIENT_IP_HEADER, "203.0.113.9")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 51234))
                .build()));

        // And where there is nothing at all to resolve, the forged value is
        // still replaced rather than left in place.
        assertEquals("unknown", stamp(1, post()
                .header(ClientIpConfig.CLIENT_IP_HEADER, "203.0.113.9")
                .build()));
    }

    @Test
    @DisplayName("the forwarded chain is gone once the transformer has run, which is why the stamp exists")
    void forwardedHeadersAreStrippedByTheFramework() {
        ServerHttpRequest transformed = new ClientIpStampingTransformer(1).apply(post()
                .header(ClientIpConfig.FORWARDED_FOR, "203.0.113.9, " + REAL)
                .build());

        assertNull(transformed.getHeaders().getFirst(ClientIpConfig.FORWARDED_FOR),
                "a controller cannot re-read the chain, so resolution has to happen here");
    }

    @Test
    @DisplayName("the trusted hop count moves the index, so a proxy in front is a config change")
    void trustedHopCountIsHonoured() {
        // What an external HTTPS load balancer or Cloud Armor in front of Cloud
        // Run produces: the proxy appends the caller, Cloud Run then appends the
        // proxy. The caller is second from the right.
        String chain = "203.0.113.9, " + REAL + ", 10.128.0.5";

        assertEquals(REAL, stamp(2, post().header(ClientIpConfig.FORWARDED_FOR, chain).build()));
        assertEquals("10.128.0.5", stamp(1, post().header(ClientIpConfig.FORWARDED_FOR, chain).build()),
                "and the default still takes the right-most, which is this deployment's topology");
    }

    @Test
    @DisplayName("a hop count that cannot be satisfied never walks past either end of the chain")
    void hopCountIsClamped() {
        // Below one would index off the right-hand end and back onto the
        // caller-supplied side.
        assertEquals(REAL, stamp(0, post().header(ClientIpConfig.FORWARDED_FOR, "203.0.113.9, " + REAL).build()));

        // More hops than entries means every entry was written by trusted
        // infrastructure, so the left-most is the client-most value there is.
        assertEquals(REAL, stamp(3, post().header(ClientIpConfig.FORWARDED_FOR, REAL).build()));
    }

    @Test
    @DisplayName("the bean keeps the name and type the framework looks for")
    void beanIsWiredWhereSpringWillFindIt() throws Exception {
        Method bean = ClientIpConfig.class
                .getDeclaredMethod("trustedHopForwardedHeaderTransformer", int.class);

        // WebHttpHandlerBuilder fetches this BY NAME and falls back to no
        // transformer at all if the name does not match, silently.
        assertArrayEquals(
                new String[] {WebHttpHandlerBuilder.FORWARDED_HEADER_TRANSFORMER_BEAN_NAME},
                bean.getAnnotation(Bean.class).value());

        // The declared type is pinned at the supertype the framework names,
        // because two separate mechanisms key off it: the by-name lookup above
        // is getBean(name, ForwardedHeaderTransformer.class), and
        // ReactiveWebServerFactoryAutoConfiguration's @ConditionalOnMissingBean
        // deduces the same type from its own @Bean return type, which is what
        // makes it back off. Both match by assignability, so a subclass would
        // still work; declaring something outside the hierarchy under this name
        // fails the context at startup rather than reverting quietly.
        assertEquals(ForwardedHeaderTransformer.class, bean.getReturnType());

        assertEquals(REAL, stamp(ClientIpConfig.DEFAULT_TRUSTED_HOP_COUNT,
                post().header(ClientIpConfig.FORWARDED_FOR, "203.0.113.9, " + REAL).build()),
                "the documented default is the one Cloud Run without a load balancer needs");
    }

    @Test
    @DisplayName("AuthController throttles on the stamped address, not on anything the caller sent")
    void authControllerUsesTheResolvedAddress() {
        AuthService authService = mock(AuthService.class);
        LoginThrottle loginThrottle = mock(LoginThrottle.class);
        when(loginThrottle.checkAddress(anyString())).thenReturn(Mono.empty());
        when(loginThrottle.recordAddressAttempt(anyString())).thenReturn(Mono.empty());
        when(authService.register(any())).thenReturn(Mono.just(AuthResponse.builder().build()));

        AuthController controller = new AuthController(
                authService, loginThrottle, mock(TokenVersionCache.class), mock(JwtTokenProvider.class));

        // A leftover chain on the request must not outrank the stamp. In
        // production it is stripped before a handler runs; here it stands in for
        // anything a caller might get in front of the resolved value.
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/auth/register")
                .header(ClientIpConfig.FORWARDED_FOR, "203.0.113.9")
                .header(ClientIpConfig.CLIENT_IP_HEADER, REAL)
                .build());

        controller.register(new RegisterRequest(), exchange).block();

        verify(loginThrottle).checkAddress(REAL);
        verify(loginThrottle).recordAddressAttempt(REAL);
    }

    @Test
    @DisplayName("VisitorSignalController counts the stamped address, so visits cannot be forged apart")
    void visitorControllerUsesTheResolvedAddress() {
        VisitorSignalService service = mock(VisitorSignalService.class);
        when(service.record(any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());

        VisitorSignalController controller = new VisitorSignalController(service);

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/events")
                .header(ClientIpConfig.FORWARDED_FOR, "203.0.113.9")
                .header(ClientIpConfig.CLIENT_IP_HEADER, REAL)
                .build());

        controller.record(Map.of("event", "page_view"), exchange).block();

        // The salted daily visitor key is built from this. With the old
        // left-most read, one visitor could be counted as as many people as they
        // cared to invent.
        verify(service).record(eq("page_view"), any(), any(), any(), eq(REAL), any());
    }
}
