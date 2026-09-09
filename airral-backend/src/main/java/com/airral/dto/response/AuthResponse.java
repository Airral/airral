package com.airral.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {

    private String token;
    
    @Builder.Default
    private String type = "Bearer";
    
    // User information
    private Long userId;
    private String email;
    private String firstName;
    private String lastName;
    private String role;
    
    // Organization information
    private Long organizationId;
    private String organizationName;
    private String organizationTier;
    
    // Additional flags
    private Boolean isPlatformAdmin;
    private Boolean emailVerified;
    
    private String message;

    /**
     * True when this call created the account, rather than signing in to one.
     *
     * <p>The client needs to know so it can send a first-time user to onboarding.
     * It used to infer this from which tab of the sign-in form was open, which is
     * wrong for "Continue with Google": one button serves both a new user and a
     * returning one, and only the server knows which happened.
     */
    private Boolean accountCreated;
}
