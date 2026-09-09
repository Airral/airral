package com.airral.domain;

import com.airral.domain.enums.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("users")
public class User {

    @Id
    private Long id;

    private String email;
    private String passwordHash;

    /**
     * Google's "sub" for the linked Google account, or null when this account
     * has never been linked to one.
     *
     * <p>The link has to be a stored fact rather than an address comparison.
     * Anyone can register any address here and nothing verifies it, so matching
     * a Google credential on its email alone would sign the real owner of the
     * address into whichever row had claimed it first.
     */
    private String googleSubject;

    private String firstName;
    private String lastName;
    private String phone;

    // Organization relationship (Multi-tenancy)
    private Long organizationId;

    // Role within the organization
    private UserRole role;

    // Platform-level access
    private Boolean isPlatformAdmin;

    // Manager hierarchy
    private Long managerId;
    private String department;
    private String jobTitle;
    private Long departmentId; // FK to departments table

    // Status & verification
    private Boolean isActive;
    private Boolean emailVerified;

    /**
     * Session generation. Incrementing it invalidates every token already
     * issued to this user, which is what makes a stateless session revocable.
     */
    private Integer tokenVersion;
    private String invitationToken;
    private LocalDateTime invitationExpiresAt;

    // Timestamps
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastLoginAt;

    // Audit trail
    private Long createdById;

    // Helper methods
    public String getFullName() {
        return firstName + " " + lastName;
    }

    public boolean isEmailVerified() {
        return Boolean.TRUE.equals(emailVerified);
    }

    public boolean isActive() {
        return Boolean.TRUE.equals(isActive);
    }

    public boolean isPlatformAdmin() {
        return Boolean.TRUE.equals(isPlatformAdmin);
    }
}
