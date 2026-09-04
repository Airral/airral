package com.airral.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.airral.security.ApiKeyScopes;
import com.fasterxml.jackson.databind.JsonNode;

import reactor.core.publisher.Mono;

/**
 * The MCP endpoint: JSON-RPC 2.0 over a single POST, which is what the
 * Streamable HTTP transport requires.
 *
 * <p>Hand-written rather than taken from an SDK. Spring AI's MCP server starter
 * would be the obvious choice, but it needs Spring Boot 3.4 and this
 * application is pinned to 3.2.5 by a dependency chain that took real work to
 * stabilise -- the Cloud SQL R2DBC connector is held at 1.25.0 because newer
 * versions pull a Netty that Boot 3.2.5 does not manage. Upgrading Boot to gain
 * a protocol adapter would risk the thing that actually keeps the service
 * booting. The protocol surface needed here is four methods.
 *
 * <p>Responses are plain {@code application/json} rather than an SSE stream.
 * The transport permits either, and every tool here answers in one shot with
 * nothing to stream. It also keeps a request from occupying one of this
 * service's 80 concurrency slots for the life of an agent's session -- the
 * thing that would eventually force this endpoint into its own deployable.
 */
@RestController
@RequestMapping("/mcp")
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    /** The revision of the MCP spec this speaks. */
    private static final String PROTOCOL_VERSION = "2025-06-18";

    // JSON-RPC 2.0 reserved codes.
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;

    private final Map<String, McpTool> tools;

    public McpController(List<McpTool> tools) {
        this.tools = tools.stream().collect(Collectors.toMap(McpTool::name, tool -> tool));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> handle(@RequestBody JsonNode request) {
        if (request == null || !request.hasNonNull("method")) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(error(null, INVALID_REQUEST, "Not a JSON-RPC request: 'method' is missing")));
        }

        String method = request.get("method").asText();
        JsonNode id = request.get("id");

        // A notification has no id and must not be answered with a result. The
        // client is not waiting, and a response to one is a protocol error.
        if (id == null || id.isNull()) {
            return Mono.just(ResponseEntity.accepted().build());
        }

        return switch (method) {
            case "initialize" -> Mono.just(ok(id, initializeResult()));
            case "ping" -> Mono.just(ok(id, Map.of()));
            case "tools/list" -> authorizedScopes()
                    .map(scopes -> ok(id, Map.of("tools", listTools(scopes))));
            case "tools/call" -> callTool(id, request.get("params"));
            default -> Mono.just(ResponseEntity.ok(
                    error(id, METHOD_NOT_FOUND, "Unsupported method: " + method)));
        };
    }

    private Map<String, Object> initializeResult() {
        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", "airral");
        serverInfo.put("version", "1.0.0");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", PROTOCOL_VERSION);
        // Only tools. No resources, prompts or sampling, and saying so keeps a
        // client from probing for capabilities that are not there.
        result.put("capabilities", Map.of("tools", Map.of()));
        result.put("serverInfo", serverInfo);
        return result;
    }

    /**
     * Only the tools this caller's key can actually use.
     *
     * <p>Listing a tool the caller cannot call would be worse than hiding it:
     * the model would choose it, get an authorisation error, and have no way to
     * tell a permission problem from a broken tool. Filtering the catalogue is
     * how an employer key and an applicant key present as different servers
     * without either knowing the other exists.
     */
    private List<Map<String, Object>> listTools(Set<String> scopes) {
        List<Map<String, Object>> listed = new ArrayList<>();
        for (McpTool tool : tools.values()) {
            if (!scopes.contains(tool.requiredScope())) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", tool.name());
            entry.put("description", tool.description());
            entry.put("inputSchema", tool.inputSchema());
            listed.add(entry);
        }
        listed.sort((a, b) -> String.valueOf(a.get("name")).compareTo(String.valueOf(b.get("name"))));
        return listed;
    }

    private Mono<ResponseEntity<Map<String, Object>>> callTool(JsonNode id, JsonNode params) {
        if (params == null || !params.hasNonNull("name")) {
            return Mono.just(ResponseEntity.ok(
                    error(id, INVALID_PARAMS, "tools/call requires a 'name'")));
        }

        String name = params.get("name").asText();
        McpTool tool = tools.get(name);
        if (tool == null) {
            return Mono.just(ResponseEntity.ok(
                    error(id, INVALID_PARAMS, "Unknown tool: " + name)));
        }

        return authorizedScopes().flatMap(scopes -> {
            if (!scopes.contains(tool.requiredScope())) {
                // A tool-level error, not a JSON-RPC one: the request was
                // well formed, and the model should read the reason and stop
                // rather than retry.
                return Mono.just(ResponseEntity.ok(toolError(id,
                        "This API key does not have the '" + tool.requiredScope()
                                + "' scope needed for " + name + ".")));
            }

            JsonNode arguments = params.get("arguments");
            return tool.call(arguments)
                    .map(text -> ResponseEntity.ok(toolResult(id, text)))
                    .onErrorResume(failure -> {
                        // The message may name internal services or SQL, so the
                        // caller gets a stable sentence and the detail goes to
                        // the log.
                        log.error("MCP tool {} failed", name, failure);
                        return Mono.just(ResponseEntity.ok(toolError(id,
                                "The " + name + " tool failed. This is a problem on AIRRAL's side, "
                                        + "not with the request.")));
                    });
        });
    }

    /**
     * Scopes granted to the caller.
     *
     * <p>A session JWT carries a role but no scopes, so a browser-authenticated
     * request would see an empty set and no tools. That is intentional: MCP is
     * reached with an API key, and a JWT arriving here means something is
     * misconfigured rather than that access should be inferred from the role.
     */
    private Mono<Set<String>> authorizedScopes() {
        return ReactiveSecurityContextHolder.getContext()
                .map(context -> context.getAuthentication())
                .map(Authentication::getAuthorities)
                .map(authorities -> authorities.stream()
                        .map(GrantedAuthority::getAuthority)
                        .filter(authority -> authority.startsWith(ApiKeyScopes.AUTHORITY_PREFIX))
                        .map(authority -> authority.substring(ApiKeyScopes.AUTHORITY_PREFIX.length()))
                        .collect(Collectors.toSet()))
                .defaultIfEmpty(Set.of());
    }

    // ── JSON-RPC envelopes ──

    private ResponseEntity<Map<String, Object>> ok(JsonNode id, Map<String, Object> result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", idValue(id));
        body.put("result", result);
        return ResponseEntity.ok(body);
    }

    private Map<String, Object> error(JsonNode id, int code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", idValue(id));
        body.put("error", Map.of("code", code, "message", message));
        return body;
    }

    private Map<String, Object> toolResult(JsonNode id, String text) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(Map.of("type", "text", "text", text)));
        result.put("isError", false);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", idValue(id));
        body.put("result", result);
        return body;
    }

    private Map<String, Object> toolError(JsonNode id, String text) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(Map.of("type", "text", "text", text)));
        result.put("isError", true);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", idValue(id));
        body.put("result", result);
        return body;
    }

    /** JSON-RPC ids may be a string or a number, and must come back unchanged. */
    private Object idValue(JsonNode id) {
        if (id == null || id.isNull()) {
            return null;
        }
        return id.isNumber() ? id.numberValue() : id.asText();
    }
}
