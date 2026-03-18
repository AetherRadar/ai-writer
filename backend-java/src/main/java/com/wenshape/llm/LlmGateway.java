package com.wenshape.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenshape.storage.ProjectStorage;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LLM 网关 - 统一管理多个 LLM 提供商
 */
@Slf4j
@Component
public class LlmGateway {
    
    @Value("${llm.openai.api-key:}")
    private String openaiApiKey;
    
    @Value("${llm.openai.base-url:https://api.openai.com/v1}")
    private String openaiBaseUrl;
    
    @Value("${llm.openai.model:gpt-4o}")
    private String openaiModel;
    
    @Value("${llm.openai.temperature:0.7}")
    private double openaiTemperature;
    
    @Value("${llm.openai.max-tokens:8000}")
    private int openaiMaxTokens;

    private static final Map<String, String> DEFAULT_BASE_URLS = Map.of(
            "openai", "https://api.openai.com/v1",
            "deepseek", "https://api.deepseek.com/v1",
            "qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "kimi", "https://api.moonshot.cn/v1",
            "glm", "https://open.bigmodel.cn/api/paas/v4",
            "gemini", "https://generativelanguage.googleapis.com/v1beta/openai/",
            "grok", "https://api.x.ai/v1"
    );

    private final ProjectStorage projectStorage;
    private final Map<String, LlmProvider> providers = new LinkedHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger totalRequests = new AtomicInteger(0);
    private final AtomicLong totalTokens = new AtomicLong(0);
    
    private static final int MAX_RETRIES = 3;
    private static final int[] RETRY_DELAYS = {1000, 2000, 4000};

    public LlmGateway(ProjectStorage projectStorage) {
        this.projectStorage = projectStorage;
    }
    
    @PostConstruct
    public synchronized void init() {
        if (openaiApiKey != null && !openaiApiKey.isBlank()) {
            providers.put("openai", new OpenAiProvider(
                    openaiApiKey, openaiBaseUrl, openaiModel, openaiMaxTokens, openaiTemperature
            ));
            log.info("Initialized OpenAI provider with model: {}", openaiModel);
        }

        loadConfiguredProfiles();

        if (providers.isEmpty()) {
            log.warn("No LLM providers configured, LLM features will be unavailable");
        }
    }
    
    /**
     * 同步聊天（带重试）
     */
    public LlmResponse chat(List<Map<String, String>> messages, String providerId,
                            Double temperature, Integer maxTokens) {
        LlmProvider provider = getProvider(providerId);
        
        Exception lastException = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                LlmResponse response = provider.chat(messages, temperature, maxTokens);
                
                totalRequests.incrementAndGet();
                totalTokens.addAndGet(response.getTotalTokens());
                
                log.info("LLM chat completed: provider={}, model={}, elapsed={}ms, tokens={}",
                        response.getProvider(), response.getModel(), 
                        response.getElapsedMs(), response.getTotalTokens());
                
                return response;
            } catch (Exception e) {
                lastException = e;
                log.warn("LLM chat failed (attempt {}/{}): {}", attempt + 1, MAX_RETRIES, e.getMessage());
                
                if (attempt < MAX_RETRIES - 1) {
                    try {
                        Thread.sleep(RETRY_DELAYS[attempt]);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during retry", ie);
                    }
                }
            }
        }
        
        throw new RuntimeException("LLM chat failed after " + MAX_RETRIES + " retries", lastException);
    }
    
    /**
     * 流式聊天
     */
    public Flux<String> streamChat(List<Map<String, String>> messages, String providerId,
                                    Double temperature, Integer maxTokens) {
        LlmProvider provider = getProvider(providerId);
        totalRequests.incrementAndGet();
        return provider.streamChat(messages, temperature, maxTokens);
    }
    
    /**
     * 简单生成（单轮对话）
     */
    public reactor.core.publisher.Mono<String> generate(String prompt, String agentName, int maxTokens) {
        String providerId = getProviderForAgent(agentName);
        List<Map<String, String>> messages = List.of(
                Map.of("role", "user", "content", prompt)
        );
        
        return reactor.core.publisher.Mono.fromCallable(() -> {
            LlmResponse response = chat(messages, providerId, null, maxTokens);
            return response.getContent();
        });
    }
    
    /**
     * 获取指定 Agent 的提供商 ID
     */
    public String getProviderForAgent(String agentName) {
        Optional<String> assigned = resolveAssignedProfileId(agentName);
        if (assigned.isPresent()) {
            return assigned.get();
        }

        synchronized (this) {
            loadConfiguredProfiles();
        }

        if (providers.containsKey("openai")) {
            return "openai";
        }
        if (!providers.isEmpty()) {
            return providers.keySet().iterator().next();
        }
        throw new IllegalStateException("No LLM provider configured");
    }
    
    /**
     * 获取统计信息
     */
    public Map<String, Object> getStats() {
        return Map.of(
                "totalRequests", totalRequests.get(),
                "totalTokens", totalTokens.get(),
                "providers", providers.keySet()
        );
    }
    
    private LlmProvider getProvider(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            providerId = getProviderForAgent("writer");
        }
        
        LlmProvider provider = providers.get(providerId);
        if (provider == null) {
            synchronized (this) {
                ensureProfileLoaded(providerId);
                provider = providers.get(providerId);
            }
        }
        if (provider == null) {
            throw new IllegalArgumentException("Provider not found: " + providerId);
        }
        return provider;
    }

    private synchronized void loadConfiguredProfiles() {
        for (Map<String, Object> profile : readProfiles()) {
            ensureProfileLoaded(profile);
        }
    }

    private Optional<String> resolveAssignedProfileId(String agentName) {
        if (agentName == null || agentName.isBlank()) {
            return Optional.empty();
        }
        Object raw = readAssignments().get(agentName);
        String profileId = raw == null ? "" : raw.toString().trim();
        if (profileId.isBlank()) {
            return Optional.empty();
        }
        synchronized (this) {
            ensureProfileLoaded(profileId);
        }
        return providers.containsKey(profileId) ? Optional.of(profileId) : Optional.empty();
    }

    private void ensureProfileLoaded(String profileId) {
        if (profileId == null || profileId.isBlank() || providers.containsKey(profileId)) {
            return;
        }
        for (Map<String, Object> profile : readProfiles()) {
            if (profileId.equals(String.valueOf(profile.getOrDefault("id", "")))) {
                ensureProfileLoaded(profile);
                return;
            }
        }
    }

    private void ensureProfileLoaded(Map<String, Object> profile) {
        String profileId = String.valueOf(profile.getOrDefault("id", "")).trim();
        if (profileId.isBlank() || providers.containsKey(profileId)) {
            return;
        }
        LlmProvider provider = createProvider(profile);
        if (provider != null) {
            providers.put(profileId, provider);
            log.info("Loaded LLM profile: {} ({})", profileId, profile.get("provider"));
        }
    }

    private LlmProvider createProvider(Map<String, Object> profile) {
        String providerId = String.valueOf(profile.getOrDefault("provider", "custom")).trim().toLowerCase();
        String apiKey = stringValue(profile.get("api_key"));
        if (apiKey.isBlank()) {
            return null;
        }

        String baseUrl = stringValue(profile.get("base_url"));
        String model = stringValue(profile.get("model"));
        int maxTokens = intValue(profile.get("max_tokens"), openaiMaxTokens);
        double temperature = doubleValue(profile.get("temperature"), openaiTemperature);

        return switch (providerId) {
            case "openai" -> new OpenAiProvider(apiKey, defaultBaseUrl(providerId, baseUrl), model, maxTokens, temperature);
            case "anthropic" -> new AnthropicProvider(apiKey, defaultBaseUrl(providerId, baseUrl), model, maxTokens, temperature);
            case "deepseek" -> new DeepSeekProvider(apiKey, defaultBaseUrl(providerId, baseUrl), model, maxTokens, temperature);
            case "qwen" -> new QwenProvider(apiKey, defaultBaseUrl(providerId, baseUrl), model, maxTokens, temperature);
            case "custom", "gemini", "grok", "kimi", "glm" ->
                    new OpenAiProvider(apiKey, defaultBaseUrl(providerId, baseUrl), model, maxTokens, temperature);
            default -> new OpenAiProvider(apiKey, defaultBaseUrl("openai", baseUrl), model, maxTokens, temperature);
        };
    }

    private String defaultBaseUrl(String providerId, String explicitBaseUrl) {
        if (explicitBaseUrl != null && !explicitBaseUrl.isBlank()) {
            return explicitBaseUrl;
        }
        return DEFAULT_BASE_URLS.getOrDefault(providerId, openaiBaseUrl);
    }

    private List<Map<String, Object>> readProfiles() {
        Path path = projectStorage.getDataPath().resolve("llm_profiles.json");
        if (!Files.exists(path)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(Files.readString(path), new TypeReference<>() {});
        } catch (IOException e) {
            log.error("读取 llm_profiles.json 失败", e);
            return List.of();
        }
    }

    private Map<String, Object> readAssignments() {
        Path path = projectStorage.getDataPath().resolve("agent_assignments.json");
        if (!Files.exists(path)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(Files.readString(path), new TypeReference<>() {});
        } catch (IOException e) {
            log.error("读取 agent_assignments.json 失败", e);
            return Map.of();
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(value.toString().trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(value.toString().trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
