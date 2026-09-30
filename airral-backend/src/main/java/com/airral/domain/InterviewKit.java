package com.airral.domain;

import io.r2dbc.postgresql.codec.Json;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/** What a company asks and rates in an interview. A job can use one. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("interview_kits")
public class InterviewKit {

    @Id
    private Long id;
    private Long organizationId;
    private String name;
    private String description;
    private Integer durationMinutes;
    /** [{"text", "category"}] */
    private Json questions;
    /** [{"name", "category", "weight"}] */
    private Json criteria;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
