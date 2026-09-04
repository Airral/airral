package com.airral.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;

import com.airral.security.ApiKeyScopes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Mono;

class McpControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpController controller;

    /** A tool that needs jobs:read and reports whether it ran. */
    private static class RecordingTool implements McpTool {
        boolean called;
        JsonNode receivedArguments;
        private final String scope;

        RecordingTool(String scope) {
            this.scope = scope;
        }

        @Override public String name() { return "search_jobs"; }
        @Override public String description() { return "Search live job postings."; }
        @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
        @Override public String requiredScope() { return scope; }

        @Override
        public Mono<String> call(JsonNode arguments) {
            called = true;
            receivedArguments = arguments;
            return Mono.just("two postings found");
        }
    }

    /** A tool that needs an employer scope, so filtering can be observed. */
    private static class EmployerTool implements McpTool {
        @Override public String name() { return "list_applicants"; }
        @Override public String description() { return "List applicants."; }
        @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
        @Override public String requiredScope() { return ApiKeyScopes.PIPELINE_READ; }
        @Override public Mono<String> call(JsonNode arguments) { return Mono.just("applicants"); }
    }

    private RecordingTool jobsTool;

    @BeforeEach
    void setUp() {
        jobsTool = new RecordingTool(ApiKeyScopes.JOBS_READ);
        controller = new McpController(List.of(jobsTool, new EmployerTool()));
    }

    // ── helpers ──

    private JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private Authentication withScopes(String... scopes) {
        List<SimpleGrantedAuthority> authorities = java.util.Arrays.stream(scopes)
                .map(scope -> new SimpleGrantedAuthority(ApiKeyScopes.authority(scope)))
                .toList();
        return new UsernamePasswordAuthenticationToken("caller@example.com", null, authorities);
    }

    private Map<String, Object> send(String request, Authentication auth) {
        ResponseEntity<Map<String, Object>> response = controller.handle(json(request))
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                .block();
        assertNotNull(response);
        return response.getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> result(Map<String, Object> body) {
        return (Map<String, Object>) body.get("result");
    }

    @SuppressWarnings("unchecked")
    private String firstText(Map<String, Object> body) {
        List<Map<String, Object>> content = (List<Map<String, Object>>) result(body).get("content");
        return String.valueOf(content.get(0).get("text"));
    }

    // ── protocol ──

    @Test
    @DisplayName("initialize advertises tools only, and echoes the protocol version")
    void initializeAdvertisesTools() {
        Map<String, Object> body = send(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}",
                withScopes(ApiKeyScopes.JOBS_READ));

        Map<String, Object> result = result(body);
        assertEquals("2025-06-18", result.get("protocolVersion"));
        assertEquals("2.0", body.get("jsonrpc"));
        // Declaring only tools stops a client probing for resources or prompts
        // that do not exist.
        assertEquals(Map.of("tools", Map.of()), result.get("capabilities"));
    }

    @Test
    @DisplayName("a notification is accepted without a response body")
    void notificationsAreNotAnswered() {
        // A notification has no id and the client is not waiting. Answering one
        // is a protocol violation, so this must not produce a result.
        ResponseEntity<Map<String, Object>> response = controller
                .handle(json("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                        withScopes(ApiKeyScopes.JOBS_READ)))
                .block();

        assertNotNull(response);
        assertEquals(202, response.getStatusCode().value());
        assertNull(response.getBody());
    }

    @Test
    @DisplayName("an unknown method returns method-not-found, not a crash")
    void unknownMethodIsRejectedCleanly() {
        Map<String, Object> body = send(
                "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"resources/list\"}",
                withScopes(ApiKeyScopes.JOBS_READ));

        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) body.get("error");
        assertEquals(-32601, error.get("code"));
    }

    @Test
    @DisplayName("a request id comes back unchanged, whether string or number")
    void idIsEchoedWithItsType() {
        // JSON-RPC allows either, and a client matching responses to requests by
        // id will not recognise 7 returned as "7".
        assertEquals(7, send("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}",
                withScopes(ApiKeyScopes.JOBS_READ)).get("id"));
        assertEquals("abc", send("{\"jsonrpc\":\"2.0\",\"id\":\"abc\",\"method\":\"ping\"}",
                withScopes(ApiKeyScopes.JOBS_READ)).get("id"));
    }

    // ── scope filtering ──

    @Test
    @DisplayName("tools/list shows only what this key can call")
    void toolListIsFilteredByScope() {
        // An applicant key must not see the employer tool. Listing a tool the
        // caller cannot use makes the model pick it and then fail, with no way
        // to tell a permission problem from a broken tool.
        Map<String, Object> body = send(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}",
                withScopes(ApiKeyScopes.JOBS_READ));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) result(body).get("tools");

        assertEquals(1, tools.size());
        assertEquals("search_jobs", tools.get(0).get("name"));
        assertNotNull(tools.get(0).get("inputSchema"), "the model needs the schema to call correctly");
    }

    @Test
    @DisplayName("an employer key sees the employer tool instead")
    void employerKeySeesEmployerTool() {
        Map<String, Object> body = send(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}",
                withScopes(ApiKeyScopes.PIPELINE_READ));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) result(body).get("tools");

        assertEquals(1, tools.size());
        assertEquals("list_applicants", tools.get(0).get("name"));
    }

    @Test
    @DisplayName("a key with no scopes sees no tools at all")
    void noScopesMeansNoTools() {
        // A session JWT carries a role but no scopes, and reaching MCP with one
        // means something is misconfigured. It must not fall back to inferring
        // access from the role.
        Map<String, Object> body = send(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}",
                new UsernamePasswordAuthenticationToken("caller@example.com", null,
                        List.of(new SimpleGrantedAuthority("APPLICANT"))));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) result(body).get("tools");
        assertTrue(tools.isEmpty());
    }

    // ── tools/call ──

    @Test
    @DisplayName("a permitted call runs the tool and returns its text")
    void permittedCallRunsTheTool() {
        Map<String, Object> body = send("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call",
                 "params":{"name":"search_jobs","arguments":{"query":"backend"}}}
                """, withScopes(ApiKeyScopes.JOBS_READ));

        assertTrue(jobsTool.called);
        assertEquals("backend", jobsTool.receivedArguments.get("query").asText());
        assertEquals("two postings found", firstText(body));
        assertEquals(false, result(body).get("isError"));
    }

    @Test
    @DisplayName("calling a tool the key lacks scope for does not run it")
    void unscopedCallIsRefusedWithoutRunning() {
        Map<String, Object> body = send("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call",
                 "params":{"name":"search_jobs","arguments":{"query":"backend"}}}
                """, withScopes(ApiKeyScopes.PIPELINE_READ));

        assertFalse(jobsTool.called, "authorisation is checked before the tool, not inside it");
        assertEquals(true, result(body).get("isError"));
        assertTrue(firstText(body).contains("jobs:read"), "the reason names the missing scope");
    }

    @Test
    @DisplayName("an unknown tool name is an invalid-params error")
    void unknownToolIsRejected() {
        Map<String, Object> body = send("""
                {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"delete_everything"}}
                """, withScopes(ApiKeyScopes.JOBS_READ));

        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) body.get("error");
        assertEquals(-32602, error.get("code"));
    }

    @Test
    @DisplayName("a failing tool reports a stable message and leaks nothing")
    void toolFailureIsContained() {
        McpTool exploding = new McpTool() {
            @Override public String name() { return "search_jobs"; }
            @Override public String description() { return "x"; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public String requiredScope() { return ApiKeyScopes.JOBS_READ; }
            @Override public Mono<String> call(JsonNode arguments) {
                return Mono.error(new IllegalStateException(
                        "relation \"external_job_postings\" does not exist"));
            }
        };
        McpController failing = new McpController(List.of(exploding));

        ResponseEntity<Map<String, Object>> response = failing.handle(json("""
                {"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"search_jobs"}}
                """))
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                        withScopes(ApiKeyScopes.JOBS_READ)))
                .block();

        assertNotNull(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.getBody().get("result");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        String text = String.valueOf(content.get(0).get("text"));

        assertEquals(true, result.get("isError"));
        assertFalse(text.contains("external_job_postings"),
                "schema names must not reach a caller, or an agent transcript, on failure");
    }

    @Test
    @DisplayName("a request with no method is a bad request, not a 500")
    void malformedRequestIsRejected() {
        ResponseEntity<Map<String, Object>> response = controller.handle(json("{\"jsonrpc\":\"2.0\"}"))
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                        withScopes(ApiKeyScopes.JOBS_READ)))
                .block();

        assertNotNull(response);
        assertEquals(400, response.getStatusCode().value());
    }
}
