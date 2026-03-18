package com.wenshape.llm;

import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * LLM 提供商接口
 */
public interface LlmProvider {
    
    /**
     * 同步聊天
     */
    LlmResponse chat(List<Map<String, String>> messages, Double temperature, Integer maxTokens);
    
    /**
     * 流式聊天
     */
    Flux<String> streamChat(List<Map<String, String>> messages, Double temperature, Integer maxTokens);
    
    /**
     * 获取提供商名称
     */
    String getProviderName();
}
