package com.wenshape.model.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 事实条目
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Fact {
    
    private String id;
    private String title;
    private String statement;
    private String content;
    private String source;
    
    @JsonProperty("introduced_in")
    private String introducedIn;
    
    @Builder.Default
    private Double confidence = 1.0;

    @JsonProperty("origin")
    private String origin;
}
