package com.airral.service;

public record ExternalJobSourceRecord(
        Long id,
        Long companyId,
        String companyName,
        String companyDomain,
        String sourceType,
        String boardToken,
        String sourceName
) {
}
