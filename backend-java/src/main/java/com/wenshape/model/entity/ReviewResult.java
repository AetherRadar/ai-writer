package com.wenshape.model.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 审稿结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewResult {

    private String chapter;

    @JsonProperty("draft_version")
    private String draftVersion;

    @Builder.Default
    private List<Issue> issues = new ArrayList<>();

    @JsonProperty("overall_assessment")
    private String overallAssessment;

    @JsonProperty("can_proceed")
    private boolean canProceed;
}
