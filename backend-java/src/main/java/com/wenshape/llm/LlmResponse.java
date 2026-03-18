package com.wenshape.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * LLM 响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmResponse {
    
    private String content;
    private String model;
    private String finishReason;
    private String provider;
    private long elapsedMs;
    
    private int promptTokens;
    private int completionTokens;
    private int totalTokens;
}
