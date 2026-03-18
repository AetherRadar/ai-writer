package com.wenshape.model.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 章节摘要
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChapterSummary {
    
    private String chapter;
    
    @JsonProperty("volume_id")
    private String volumeId;
    
    @JsonProperty("order_index")
    private Integer orderIndex;
    
    @Builder.Default
    private String title = "";
    
    @JsonProperty("word_count")
    @Builder.Default
    private int wordCount = 0;
    
    @JsonProperty("key_events")
    @Builder.Default
    private List<String> keyEvents = new ArrayList<>();
    
    @JsonProperty("new_facts")
    @Builder.Default
    private List<String> newFacts = new ArrayList<>();
    
    @JsonProperty("character_state_changes")
    @Builder.Default
    private List<String> characterStateChanges = new ArrayList<>();
    
    @JsonProperty("open_loops")
    @Builder.Default
    private List<String> openLoops = new ArrayList<>();
    
    @JsonProperty("brief_summary")
    @Builder.Default
    private String briefSummary = "";
}
