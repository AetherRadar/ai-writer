package com.wenshape.llm;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 通义千问 LLM 提供商
 * 使用 OpenAI 兼容接口
 */
@Slf4j
public class QwenProvider implements LlmProvider {
    
    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String DEFAULT_MODEL = "qwen-max";
    
    private final OpenAiProvider delegate;
    
    public QwenProvider(String apiKey, String baseUrl, String model, int maxTokens, double temperature) {
        String actualBaseUrl = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : DEFAULT_BASE_URL;
        String actualModel = (model != null && !model.isBlank()) ? model : DEFAULT_MODEL;
        
        this.delegate = new OpenAiProvider(apiKey, actualBaseUrl, actualModel, maxTokens, temperature);
        log.info("Initialized Qwen provider with model: {}", actualModel);
    }
    
    @Override
    public LlmResponse chat(List<Map<String, String>> messages, Double temperature, Integer maxTokens) {
        LlmResponse response = delegate.chat(messages, temperature, maxTokens);
        return LlmResponse.builder()
                .content(response.getContent())
                .model(response.getModel())
                .provider("qwen")
                .promptTokens(response.getPromptTokens())
                .completionTokens(response.getCompletionTokens())
                .totalTokens(response.getTotalTokens())
                .elapsedMs(response.getElapsedMs())
                .build();
    }
    
    @Override
    public Flux<String> streamChat(List<Map<String, String>> messages, Double temperature, Integer maxTokens) {
        return delegate.streamChat(messages, temperature, maxTokens);
    }
    
    @Override
    public String getProviderName() {
        return "qwen";
    }
}
