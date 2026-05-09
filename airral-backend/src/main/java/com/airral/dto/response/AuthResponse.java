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
}
