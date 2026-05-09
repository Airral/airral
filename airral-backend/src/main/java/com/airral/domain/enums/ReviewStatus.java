package com.airral.domain.enums;

public enum ReviewStatus {
    DRAFT,          // Manager is writing
    SUBMITTED,      // Manager submitted, employee can view
    ACKNOWLEDGED,   // Employee has seen it
    ARCHIVED        // Past review, archived
}
