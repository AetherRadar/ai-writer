package com.wenshape.model.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 分卷实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Volume {
    
    private String id;
    
    @JsonProperty("project_id")
    private String projectId;
    
    private String title;
    private String summary;
    
    @Builder.Default
    private int order = 1;
    
    @JsonProperty("created_at")
    private LocalDateTime createdAt;
    
    @JsonProperty("updated_at")
    private LocalDateTime updatedAt;
}
