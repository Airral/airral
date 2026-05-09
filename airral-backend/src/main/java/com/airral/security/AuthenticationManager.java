package com.airral.security;

import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class AuthenticationManager implements ReactiveAuthenticationManager {

    private final JwtTokenProvider jwtTokenProvider;

    public AuthenticationManager(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    public Mono<Authentication> authenticate(Authentication authentication) {
        String authToken = authentication.getCredentials().toString();

        try {
            if (!jwtTokenProvider.validateToken(authToken)) {
                return Mono.empty();
            }

            String email = jwtTokenProvider.getEmailFromToken(authToken);
            String role = jwtTokenProvider.getRoleFromToken(authToken);
            Long organizationId = jwtTokenProvider.getOrganizationIdFromToken(authToken);
            Boolean isPlatformAdmin = jwtTokenProvider.isPlatformAdminFromToken(authToken);

            // Create authorities from role
            List<SimpleGrantedAuthority> authorities = List.of(
                    new SimpleGrantedAuthority(role)
            );

            // Create custom authentication with organization context
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    email,
                    null,
                    authorities
            );

            // Add organization context to authentication details
            auth.setDetails(new AuthenticationDetails(
                    jwtTokenProvider.getUserIdFromToken(authToken),
                    organizationId,
                    jwtTokenProvider.getOrganizationTierFromToken(authToken),
                    role,
                    isPlatformAdmin
            ));

            return Mono.just(auth);
        } catch (Exception e) {
            return Mono.empty();
        }
    }

    /**
     * Custom authentication details to hold organization context
     */
    public static class AuthenticationDetails {
        private final Long userId;
        private final Long organizationId;
        private final String organizationTier;
        private final String role;
        private final Boolean isPlatformAdmin;

        public AuthenticationDetails(Long userId, Long organizationId, String organizationTier, 
                                    String role, Boolean isPlatformAdmin) {
            this.userId = userId;
            this.organizationId = organizationId;
            this.organizationTier = organizationTier;
            this.role = role;
            this.isPlatformAdmin = isPlatformAdmin;
        }

        public Long getUserId() { return userId; }
        public Long getOrganizationId() { return organizationId; }
        public String getOrganizationTier() { return organizationTier; }
        public String getRole() { return role; }
        public Boolean isPlatformAdmin() { return isPlatformAdmin; }
    }
}
