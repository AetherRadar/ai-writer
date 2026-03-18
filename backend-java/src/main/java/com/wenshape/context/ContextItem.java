package com.wenshape.context;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 单个上下文项
 */
@Data
@Builder
public class ContextItem {
    private String id;
    private ContextType type;
    private String content;
    @Builder.Default
    private ContextPriority priority = ContextPriority.MEDIUM;
    @Builder.Default
    private double relevanceScore = 1.0;
    @Builder.Default
    private int tokenCount = 0;
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
    
    /**
     * 估算 token 数量（简化实现：中文约2字符/token，英文约4字符/token）
     */
    public int estimateTokens() {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        // 简化估算：混合文本约 2.5 字符/token
        return (int) Math.ceil(content.length() / 2.5);
    }
    
    /**
     * 返回压缩版本
     */
    public ContextItem compressed(double ratio) {
        if (ratio >= 1.0 || content == null) {
            return this;
        }
        
        int targetLength = (int) (content.length() * ratio);
        String compressedContent = content.length() > targetLength 
                ? content.substring(0, targetLength) + "..."
                : content;
        
        Map<String, Object> newMetadata = new HashMap<>(metadata);
        newMetadata.put("compressed", true);
        newMetadata.put("original_tokens", tokenCount);
        newMetadata.put("compression_ratio", ratio);
        
        return ContextItem.builder()
                .id(id)
                .type(type)
                .content(compressedContent)
                .priority(priority)
                .relevanceScore(relevanceScore)
                .tokenCount(estimateTokens())
                .metadata(newMetadata)
                .createdAt(createdAt)
                .build();
    }
    
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("id", id);
        map.put("type", type.getValue());
        map.put("content", content);
        map.put("priority", priority.getValue());
        map.put("relevance_score", relevanceScore);
        map.put("token_count", tokenCount);
        map.put("metadata", metadata);
        return map;
    }
}
