package com.wenshape.model.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 场景简报
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SceneBrief {
    
    private String chapter;
    private String title;
    private String goal;
    
    @Builder.Default
    private List<Map<String, String>> characters = new ArrayList<>();
    
    @JsonProperty("timeline_context")
    @Builder.Default
    private Map<String, String> timelineContext = new HashMap<>();
    
    @JsonProperty("world_constraints")
    @Builder.Default
    private List<String> worldConstraints = new ArrayList<>();
    
    @Builder.Default
    private List<String> facts = new ArrayList<>();
    
    @JsonProperty("style_reminder")
    private String styleReminder;
    
    @Builder.Default
    private List<String> forbidden = new ArrayList<>();
}
