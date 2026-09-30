package com.airral.mcp;

/**
 * Who is calling a tool, as the key resolved it on this request.
 *
 * <p>Role and organisation are checked against the user's row on every request
 * (ApiKeyStore.resolve), so a tool can trust them: a key whose owner changed
 * role or company does not get this far. A tool that reads company data must
 * scope every query to {@link #organizationId}.
 */
public record McpCaller(Long userId, Long organizationId, String role) {
}
