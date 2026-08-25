package com.airral.dto.response;

import com.airral.service.ResumeHealthScoreService.CategoryScore;
import com.airral.service.ResumeHealthScoreService.ResumeIssue;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResumeHealthResponse {

    private int score;
    private String grade;
    private Map<String, CategoryScore> categories;
    private List<ResumeIssue> issues;
    private List<String> topFixes;
    private int wordCount;
    private int skillCount;
}
