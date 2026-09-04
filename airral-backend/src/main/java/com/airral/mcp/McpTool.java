package com.airral.mcp;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import reactor.core.publisher.Mono;

/**
 * One tool an agent can call.
 *
 * <p>The name, description and schema are not incidental: they are the entire
 * interface the model reasons over when deciding what to call and with what.
 * A vague description produces wrong calls far more often than a missing
 * feature does, so they are written for a reader rather than derived from the
 * endpoint underneath.
 */
public interface McpTool {

    /** snake_case, because that is the convention agents see across MCP servers. */
    String name();

    /**
     * What this does and when to reach for it, in the second person. The model
     * sees this and nothing else about the tool's behaviour.
     */
    String description();

    /** JSON Schema for the arguments object. */
    Map<String, Object> inputSchema();

    /**
     * The scope a caller's key must hold. Checked before the tool runs, so a
     * tool never has to think about authorisation itself.
     */
    String requiredScope();

    /**
     * Run it. Returns the text the agent will read.
     *
     * <p>Text rather than raw JSON because the consumer is a language model:
     * a compact, labelled rendering costs fewer tokens and gets reasoned over
     * more reliably than a deeply nested object it has to traverse.
     */
    Mono<String> call(JsonNode arguments);
}
