package com.wenshape.model.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VolumeSummary {

    @JsonProperty("volume_id")
    private String volumeId;

    @JsonProperty("brief_summary")
    @Builder.Default
    private String briefSummary = "";

    @JsonProperty("key_themes")
    @Builder.Default
    private List<String> keyThemes = new ArrayList<>();

    @JsonProperty("major_events")
    @Builder.Default
    private List<String> majorEvents = new ArrayList<>();

    @JsonProperty("chapter_count")
    @Builder.Default
    private int chapterCount = 0;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @JsonProperty("updated_at")
    private LocalDateTime updatedAt;
}
