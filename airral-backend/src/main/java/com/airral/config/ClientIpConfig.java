package com.airral.config;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.adapter.ForwardedHeaderTransformer;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;

/**
 * Decides who the caller is, once, somewhere the caller cannot reach.
 *
 * <p><b>What was wrong.</b> Rate limiting was keyed on the <em>left-most</em>
 * entry of X-Forwarded-For. Cloud Run does not strip a caller-supplied
 * X-Forwarded-For, it <em>appends</em> the real client address to it, so the
 * left-most entry is whatever the caller typed. Rotating that header handed out
 * a fresh bucket on every request. That matters most on /api/auth/register and
 * /api/auth/google, which check only LoginThrottle's address bucket, and the
 * address bucket is the only limit that spans accounts: with its key forgeable,
 * account enumeration through the 409 on register, and password spraying across
 * thousands of accounts, were effectively unlimited. The same left-most read fed
 * the salted daily visitor key, so the visitor counts could be forged too.
 *
 * <p><b>Why the fix lives in a transformer and not a WebFilter.</b>
 * {@code server.forward-headers-strategy: framework} makes Spring Boot register
 * a {@link ForwardedHeaderTransformer}, and
 * {@code HttpWebHandlerAdapter.handle} applies it to the raw
 * {@link ServerHttpRequest} <em>before</em> it creates the ServerWebExchange, so
 * before any WebFilter or handler exists. That transformer then removes every
 * Forwarded and X-Forwarded-* header on its way out, and rewrites
 * getRemoteAddress() from the same left-most entry
 * ({@code ForwardedHeaderUtils.parseForwardedFor} takes token 0). By the time a
 * filter could run, the chain is gone and the remote address is already the
 * attacker's value. The transformer is the only thing that sees the truth.
 *
 * <p><b>How this replaces the stock bean.</b>
 * {@code ReactiveWebServerFactoryAutoConfiguration} declares it as
 * {@code @Bean @ConditionalOnMissingBean @ConditionalOnProperty(server.forward-headers-strategy=framework)},
 * so any bean of this type here makes the auto-configuration back off. The bean
 * <em>name</em> is load-bearing as well:
 * {@code WebHttpHandlerBuilder.applicationContext} fetches it by name, as
 * {@code getBean("forwardedHeaderTransformer", ForwardedHeaderTransformer.class)},
 * and silently falls back to no transformer at all if the name does not match.
 * Hence the constant below rather than a hand-typed string or a method name.
 *
 * <p><b>forward-headers-strategy is unchanged, on purpose.</b> It stays
 * {@code framework}. Its scheme rewrite is what lets Spring Security see the
 * request as secure and emit Strict-Transport-Security on Cloud Run, and a
 * previous incident here came from a forwarded-header rewrite (an unresolved
 * remote address whose getAddress() was null, which turned every sign-in into a
 * 500). This subclass keeps every behaviour of the stock transformer, including
 * that remote-address rewrite, and only adds a header.
 *
 * <p><b>Why getRemoteAddress() is still not the answer.</b> Deliberately left as
 * the framework computes it, so nothing about the existing request handling
 * moves. It is therefore still derived from the spoofable left-most entry and
 * must not be used as a rate-limit key. {@link #clientAddress} is the only
 * supported source, and it consults the remote address only when there was no
 * forwarded chain at all.
 */
@Configuration
public class ClientIpConfig {

    /**
     * Carries the resolved address from the transformer to the handlers.
     *
     * <p>An internal header is the only vehicle available: the transformer runs
     * before the exchange exists, so there is no attribute map to write to yet.
     * It is safe here only because the transformer overwrites this name on
     * <em>every</em> request, so an inbound copy never survives to be read. See
     * {@link ClientIpStampingTransformer#apply}.
     */
    public static final String CLIENT_IP_HEADER = "X-Airral-Client-Ip";

    /**
     * How many entries at the right-hand end of X-Forwarded-For were written by
     * infrastructure we control, and therefore how far from the right the
     * caller's own address sits.
     *
     * <p>One, because Cloud Run receives traffic directly: domain mappings only,
     * no external HTTPS load balancer and no Cloud Armor in front. Exactly one
     * trusted hop appends to the chain, the Google front end that terminates
     * TLS, and what it appends is the address it accepted the connection from.
     * So the right-most entry is the caller and everything to its left is
     * whatever the caller chose to send.
     *
     * <p>Put a load balancer or Cloud Armor in front and this becomes two: that
     * proxy appends the caller, and Cloud Run then appends the proxy. Raising it
     * is then a config change rather than a silent return to a spoofable value.
     * The number must match the real topology in both directions -- set it
     * higher than the number of proxies that actually append and the index walks
     * left into caller-supplied entries again.
     */
    public static final int DEFAULT_TRUSTED_HOP_COUNT = 1;

    static final String FORWARDED_FOR = "X-Forwarded-For";

    private static final String UNKNOWN = "unknown";

    @Bean(WebHttpHandlerBuilder.FORWARDED_HEADER_TRANSFORMER_BEAN_NAME)
    ForwardedHeaderTransformer trustedHopForwardedHeaderTransformer(
            @Value("${airral.client-ip.trusted-hop-count:" + DEFAULT_TRUSTED_HOP_COUNT + "}") int trustedHopCount) {
        return new ClientIpStampingTransformer(trustedHopCount);
    }

    /**
     * The caller's address as seen from outside, for use as a rate-limiting or
     * bucketing key.
     *
     * <p>The stamped header first: in this deployment it is always present,
     * because the transformer writes it on every request before anything else
     * runs, and it is the only value that survives the forwarded headers being
     * stripped.
     *
     * <p>The chain second. That branch is reached only by a request that never
     * passed through the transformer, which means the forwarded headers were
     * never applied and never removed, so the chain is still intact and is read
     * the same way -- from the right. It uses the default hop count rather than
     * the configured one because no transformer means no Cloud Run either; the
     * cases are unit tests and a locally assembled handler.
     *
     * <p>Then the socket peer, which is what local development has and what a
     * request with no proxy in front of it has. Failing to determine an address
     * must never fail the request: an address is only ever a bucket key, and
     * "unknown" simply shares a bucket.
     */
    public static String clientAddress(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        HttpHeaders headers = request.getHeaders();

        String stamped = headers.getFirst(CLIENT_IP_HEADER);
        if (stamped != null && !stamped.isBlank()) {
            return stamped.trim();
        }

        String fromChain = fromForwardedChain(headers, DEFAULT_TRUSTED_HOP_COUNT);
        return fromChain != null ? fromChain : fromRemoteAddress(request.getRemoteAddress());
    }

    /**
     * The trusted entry of the forwarded chain, or null if there is no usable
     * chain to read.
     *
     * <p>Every header line, not just the first: a caller may send
     * X-Forwarded-For as several lines, and RFC 7230 makes those one
     * comma-joined chain in order. Reading only the first line would let a
     * caller put a whole chain of their own in it, including its right-hand
     * end, which is precisely the side this code trusts.
     *
     * <p>Split with tokenizeToStringArray, and deliberately <em>not</em> with
     * HttpHeaders.getValuesAsList, which is what this method used first.
     * getValuesAsList honours RFC 7230 quoted strings; X-Forwarded-For has no
     * quoting, so a single unbalanced double quote makes that tokenizer
     * swallow the comma Cloud Run's appended address is joined by. Measured
     * against spring-web 6.1.6: a caller sending {@code "evil} arrives as
     * {@code "evil, 198.51.100.7} and getValuesAsList returns the one entry
     * {@code evil, 198.51.100.7}, so the right-most entry is again a string
     * the caller chose -- the same fresh-bucket-per-request hole, one
     * character away from the original. A quoted comma also merges two real
     * entries, which shifts the index for any hop count above one.
     * tokenizeToStringArray is quote-blind, is what Spring's own
     * ForwardedHeaderUtils uses on this header, and trims each entry and drops
     * empty ones -- so padding a chain with separators cannot shift the index
     * either.
     */
    private static String fromForwardedChain(HttpHeaders headers, int trustedHopCount) {
        List<String> lines = headers.get(FORWARDED_FOR);
        if (lines == null) {
            return null;
        }

        List<String> entries = new ArrayList<>();
        for (String value : lines) {
            if (value != null) {
                Collections.addAll(entries, StringUtils.tokenizeToStringArray(value, ","));
            }
        }
        if (entries.isEmpty()) {
            return null;
        }

        // Count from the right. Clamped at zero for a chain shorter than the
        // configured hops: every entry in it was then written by trusted
        // infrastructure, so the left-most is the client-most value we have.
        return entries.get(Math.max(0, entries.size() - trustedHopCount));
    }

    private static String fromRemoteAddress(InetSocketAddress remote) {
        if (remote != null) {
            if (remote.getAddress() != null) {
                return remote.getAddress().getHostAddress();
            }
            // Unresolved, which is what the forwarded-header rewrite leaves
            // behind. The host string still names the peer.
            if (remote.getHostString() != null && !remote.getHostString().isBlank()) {
                return remote.getHostString().trim();
            }
        }
        return UNKNOWN;
    }

    /**
     * The stock transformer, plus a trustworthy address stamped onto the
     * request.
     */
    static final class ClientIpStampingTransformer extends ForwardedHeaderTransformer {

        private final int trustedHopCount;

        ClientIpStampingTransformer(int trustedHopCount) {
            // Zero or negative would index past the right-hand end and land back
            // on the caller-supplied side of the chain.
            this.trustedHopCount = Math.max(1, trustedHopCount);
        }

        @Override
        public ServerHttpRequest apply(ServerHttpRequest request) {
            // Resolved from the request as it arrived, because super.apply()
            // removes X-Forwarded-For before returning. If super throws on a
            // malformed chain the request is rejected with a 400 exactly as it
            // was before this class existed, and no handler runs unstamped.
            String resolved = resolve(request);

            ServerHttpRequest transformed = super.apply(request);

            // set(), not add(): one value, ours, replacing anything the caller
            // sent under this name. This is the whole defence for using a header
            // as the vehicle. It runs on every request, with no branch that can
            // skip it, and before the exchange exists -- so there is no request
            // in which an inbound copy reaches a filter or a handler. resolve()
            // never reads this header either, so a copy cannot steer the value.
            //
            // The cost is one extra request wrapper per request on top of the
            // one super already makes. Measured against the alternative, which
            // is leaving the only cross-account rate limit forgeable.
            return transformed.mutate()
                    .headers(headers -> headers.set(CLIENT_IP_HEADER, resolved))
                    .build();
        }

        private String resolve(ServerHttpRequest request) {
            String fromChain = fromForwardedChain(request.getHeaders(), this.trustedHopCount);
            return fromChain != null ? fromChain : fromRemoteAddress(request.getRemoteAddress());
        }
    }
}
