package com.wenshape.model.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 项目实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Project {
    
    private String id;
    private String name;
    private String description;
    
    @JsonProperty("language")
    @Builder.Default
    private String language = "zh";
    
    @JsonProperty("created_at")
    private LocalDateTime createdAt;
    
    @JsonProperty("updated_at")
    private LocalDateTime updatedAt;
    
    @JsonProperty("chapter_count")
    @Builder.Default
    private int chapterCount = 0;
    
    @JsonProperty("total_word_count")
    @Builder.Default
    private int totalWordCount = 0;
}
