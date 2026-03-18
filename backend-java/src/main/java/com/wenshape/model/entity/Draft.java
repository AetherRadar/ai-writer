package com.wenshape.model.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 草稿实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Draft {
    
    private String chapter;
    private String version;
    private String content;
    
    @JsonProperty("word_count")
    private int wordCount;
    
    @JsonProperty("pending_confirmations")
    @Builder.Default
    private List<String> pendingConfirmations = new ArrayList<>();
    
    @JsonProperty("created_at")
    private LocalDateTime createdAt;
}
