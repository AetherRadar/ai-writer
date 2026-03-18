package com.wenshape.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI 兼容提供商 - 直接使用 OkHttp（强制 HTTP/1.1，兼容反代服务）
 */
@Slf4j
public class OpenAiProvider implements LlmProvider {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int maxTokens;
    private final double temperature;
    private final OkHttpClient httpClient;

    public OpenAiProvider(String apiKey, String baseUrl, String model, int maxTokens, double temperature) {
        this.apiKey = apiKey;
        this.baseUrl = (baseUrl != null ? baseUrl : "https://api.openai.com/v1")
                .replaceAll("/+$", "");
        this.model = model != null ? model : "gpt-4o";
        this.maxTokens = maxTokens > 0 ? maxTokens : 8000;
        this.temperature = temperature;

        // 强制 HTTP/1.1，避免 HTTP/2 SETTINGS preface 握手失败
        this.httpClient = new OkHttpClient.Builder()
                .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public LlmResponse chat(List<Map<String, String>> messages, Double temperature, Integer maxTokens) {
        long startTime = System.currentTimeMillis();

        try {
            String requestBody = buildRequestBody(messages,
                    temperature != null ? temperature : this.temperature,
                    maxTokens != null ? maxTokens : this.maxTokens,
                    false);

            Request request = new Request.Builder()
                    .url(baseUrl + "/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody, JSON))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String body = response.body() != null ? response.body().string() : "(empty)";
                    throw new RuntimeException("LLM API error " + response.code() + ": " + body);
                }

                String responseBody = response.body().string();
                JsonNode root = MAPPER.readTree(responseBody);

                String content = root.path("choices").path(0)
                        .path("message").path("content").asText("");
                String finishReason = root.path("choices").path(0)
                        .path("finish_reason").asText("stop");
                int promptTokens = root.path("usage").path("prompt_tokens").asInt(0);
                int completionTokens = root.path("usage").path("completion_tokens").asInt(0);

                return LlmResponse.builder()
                        .content(content)
                        .model(model)
                        .finishReason(finishReason)
                        .provider(getProviderName())
                        .elapsedMs(System.currentTimeMillis() - startTime)
                        .promptTokens(promptTokens)
                        .completionTokens(completionTokens)
                        .totalTokens(promptTokens + completionTokens)
                        .build();
            }
        } catch (IOException e) {
            throw new RuntimeException("LLM chat failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Flux<String> streamChat(List<Map<String, String>> messages, Double temperature, Integer maxTokens) {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

        Thread.ofVirtual().start(() -> {
            try {
                String requestBody = buildRequestBody(messages,
                        temperature != null ? temperature : this.temperature,
                        maxTokens != null ? maxTokens : this.maxTokens,
                        true);

                Request request = new Request.Builder()
                        .url(baseUrl + "/chat/completions")
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .post(RequestBody.create(requestBody, JSON))
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        String body = response.body() != null ? response.body().string() : "(empty)";
                        sink.tryEmitError(new RuntimeException("LLM stream error " + response.code() + ": " + body));
                        return;
                    }

                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.startsWith("data: ")) {
                                String data = line.substring(6).trim();
                                if ("[DONE]".equals(data)) break;
                                try {
                                    JsonNode node = MAPPER.readTree(data);
                                    String token = node.path("choices").path(0)
                                            .path("delta").path("content").asText("");
                                    if (!token.isEmpty()) {
                                        sink.tryEmitNext(token);
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                    sink.tryEmitComplete();
                }
            } catch (Exception e) {
                log.error("Stream chat failed", e);
                sink.tryEmitError(e);
            }
        });

        return sink.asFlux();
    }

    @Override
    public String getProviderName() {
        return "openai";
    }

    private String buildRequestBody(List<Map<String, String>> messages, double temp, int maxTok, boolean stream)
            throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        root.put("temperature", temp);
        root.put("max_tokens", maxTok);
        root.put("stream", stream);

        ArrayNode msgs = root.putArray("messages");
        for (Map<String, String> msg : messages) {
            ObjectNode m = msgs.addObject();
            m.put("role", msg.getOrDefault("role", "user"));
            m.put("content", msg.getOrDefault("content", ""));
        }

        return MAPPER.writeValueAsString(root);
    }
}
