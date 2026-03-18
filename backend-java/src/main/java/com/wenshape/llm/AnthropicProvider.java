package com.wenshape.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Claude LLM 提供商
 */
@Slf4j
public class AnthropicProvider implements LlmProvider {
    
    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    private static final String DEFAULT_MODEL = "claude-3-5-sonnet-20241022";
    private static final String API_VERSION = "2023-06-01";
    
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int maxTokens;
    private final double temperature;
    
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    public AnthropicProvider(String apiKey, String baseUrl, String model, int maxTokens, double temperature) {
        this.apiKey = apiKey;
        this.baseUrl = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : DEFAULT_BASE_URL;
        this.model = (model != null && !model.isBlank()) ? model : DEFAULT_MODEL;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
        
        log.info("Initialized Anthropic provider with model: {}", this.model);
    }
    
    @Override
    public LlmResponse chat(List<Map<String, String>> messages, Double temperature, Integer maxTokens) {
        long startTime = System.currentTimeMillis();
        
        try {
            // 转换消息格式（Anthropic 使用不同的格式）
            String systemPrompt = "";
            List<Map<String, String>> userMessages = new ArrayList<>();
            
            for (Map<String, String> msg : messages) {
                String role = msg.get("role");
                String content = msg.get("content");
                
                if ("system".equals(role)) {
                    systemPrompt = content;
                } else {
                    userMessages.add(Map.of("role", role, "content", content));
                }
            }
            
            // 构建请求体
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("max_tokens", maxTokens != null ? maxTokens : this.maxTokens);
            requestBody.put("temperature", temperature != null ? temperature : this.temperature);
            requestBody.put("messages", userMessages);
            
            if (!systemPrompt.isBlank()) {
                requestBody.put("system", systemPrompt);
            }
            
            String jsonBody = objectMapper.writeValueAsString(requestBody);
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/messages"))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", API_VERSION)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(120))
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                throw new RuntimeException("Anthropic API error: " + response.statusCode() + " - " + response.body());
            }
            
            JsonNode root = objectMapper.readTree(response.body());
            
            // 提取内容
            StringBuilder contentBuilder = new StringBuilder();
            JsonNode contentArray = root.get("content");
            if (contentArray != null && contentArray.isArray()) {
                for (JsonNode block : contentArray) {
                    if ("text".equals(block.get("type").asText())) {
                        contentBuilder.append(block.get("text").asText());
                    }
                }
            }
            
            // 提取 token 使用
            JsonNode usage = root.get("usage");
            int inputTokens = usage != null ? usage.get("input_tokens").asInt(0) : 0;
            int outputTokens = usage != null ? usage.get("output_tokens").asInt(0) : 0;
            
            long elapsedMs = System.currentTimeMillis() - startTime;
            
            return LlmResponse.builder()
                    .content(contentBuilder.toString())
                    .model(model)
                    .provider("anthropic")
                    .promptTokens(inputTokens)
                    .completionTokens(outputTokens)
                    .totalTokens(inputTokens + outputTokens)
                    .elapsedMs(elapsedMs)
                    .build();
            
        } catch (Exception e) {
            log.error("Anthropic API call failed", e);
            throw new RuntimeException("Anthropic API call failed: " + e.getMessage(), e);
        }
    }
    
    @Override
    public Flux<String> streamChat(List<Map<String, String>> messages, Double temperature, Integer maxTokens) {
        // 简化实现：使用非流式调用
        return Flux.create(sink -> {
            try {
                LlmResponse response = chat(messages, temperature, maxTokens);
                // 模拟流式输出
                String content = response.getContent();
                int chunkSize = 50;
                for (int i = 0; i < content.length(); i += chunkSize) {
                    int end = Math.min(i + chunkSize, content.length());
                    sink.next(content.substring(i, end));
                }
                sink.complete();
            } catch (Exception e) {
                sink.error(e);
            }
        });
    }
    
    @Override
    public String getProviderName() {
        return "anthropic";
    }
}
