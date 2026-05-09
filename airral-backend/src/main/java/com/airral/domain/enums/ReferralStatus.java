package com.airral.domain.enums;

public enum ReferralStatus {
    PENDING,        // HR hasn't reviewed yet
    REVIEWING,      // HR is reviewing
    CONTACTED,      // HR reached out to candidate
    APPLIED,        // Converted to application
    HIRED,          // Successfully hired!
    DECLINED        // HR declined the referral
}
