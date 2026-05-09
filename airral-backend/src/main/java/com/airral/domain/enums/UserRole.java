package com.airral.domain.enums;

public enum UserRole {
    ADMIN,          // Full system access
    HR_MANAGER,     // Full HR portal access
    MANAGER,        // Team management + employee features
    EMPLOYEE,       // Self-service only
    APPLICANT       // Candidate portal access
}
