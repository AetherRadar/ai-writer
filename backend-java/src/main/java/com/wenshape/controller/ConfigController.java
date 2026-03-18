package com.wenshape.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenshape.storage.ProjectStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 配置控制器 - LLM 配置管理（持久化到 data/ 目录，与 Python 后端共享）
 */
@Slf4j
@RestController
@RequestMapping("/config")
@RequiredArgsConstructor
public class ConfigController {

    private final ProjectStorage projectStorage;

    // ========== LLM Profiles ==========

    @GetMapping("/llm/profiles")
    public List<Map<String, Object>> getProfiles() {
        return readProfiles();
    }

    @PostMapping("/llm/profiles")
    public Map<String, Object> saveProfile(@RequestBody Map<String, Object> profile) {
        String rawId = (String) profile.get("id");
        final String id = (rawId == null || rawId.isBlank()) ? UUID.randomUUID().toString() : rawId;
        profile.put("id", id);
        long now = System.currentTimeMillis() / 1000;
        profile.put("updated_at", now);
        if (!profile.containsKey("created_at")) {
            profile.put("created_at", now);
        }

        List<Map<String, Object>> profiles = readProfiles();
        profiles.removeIf(p -> id.equals(p.get("id")));
        profiles.add(profile);
        writeProfiles(profiles);

        log.info("Saved LLM profile: {}", profile.get("name"));
        return profile;
    }

    @DeleteMapping("/llm/profiles/{profileId}")
    public Map<String, Object> deleteProfile(@PathVariable String profileId) {
        List<Map<String, Object>> profiles = readProfiles();
        profiles.removeIf(p -> profileId.equals(p.get("id")));
        writeProfiles(profiles);
        log.info("Deleted LLM profile: {}", profileId);
        return Map.of("success", true);
    }

    // ========== Agent Assignments ==========

    @GetMapping("/llm/assignments")
    public Map<String, Object> getAssignments() {
        return readAssignments();
    }

    @PostMapping("/llm/assignments")
    public Map<String, Object> updateAssignments(@RequestBody Map<String, Object> newAssignments) {
        Map<String, Object> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : newAssignments.entrySet()) {
            if (e.getValue() != null && !e.getValue().toString().isBlank()) {
                filtered.put(e.getKey(), e.getValue());
            }
        }
        writeAssignments(filtered);
        log.info("Updated agent assignments: {}", filtered);
        return Map.of("success", true);
    }

    // ========== Providers Meta ==========

    @GetMapping("/llm/providers_meta")
    public List<Map<String, Object>> getProvidersMeta() {
        return List.of(
                Map.of("id", "openai", "label", "OpenAI", "fields", List.of("api_key", "model")),
                Map.of("id", "anthropic", "label", "Anthropic (Claude)", "fields", List.of("api_key", "model")),
                Map.of("id", "deepseek", "label", "DeepSeek", "fields", List.of("api_key", "model")),
                Map.of("id", "qwen", "label", "通义千问", "fields", List.of("api_key", "model")),
                Map.of("id", "custom", "label", "Custom / OpenAI Compatible",
                        "fields", List.of("base_url", "api_key", "model"))
        );
    }

    // ========== 私有方法 ==========

    private Path profilesPath() {
        return projectStorage.getDataPath().resolve("llm_profiles.json");
    }

    private Path assignmentsPath() {
        return projectStorage.getDataPath().resolve("agent_assignments.json");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readProfiles() {
        Path path = profilesPath();
        if (!Files.exists(path)) return new ArrayList<>();
        try {
            String content = Files.readString(path);
            return new ObjectMapper().readValue(content, new TypeReference<>() {});
        } catch (IOException e) {
            log.error("读取 llm_profiles.json 失败", e);
            return new ArrayList<>();
        }
    }

    private void writeProfiles(List<Map<String, Object>> profiles) {
        try {
            String content = new ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(profiles);
            Files.writeString(profilesPath(), content);
        } catch (IOException e) {
            log.error("写入 llm_profiles.json 失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readAssignments() {
        Path path = assignmentsPath();
        if (!Files.exists(path)) return new LinkedHashMap<>();
        try {
            String content = Files.readString(path);
            return new ObjectMapper().readValue(content, new TypeReference<>() {});
        } catch (IOException e) {
            log.error("读取 agent_assignments.json 失败", e);
            return new LinkedHashMap<>();
        }
    }

    private void writeAssignments(Map<String, Object> assignments) {
        try {
            String content = new ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(assignments);
            Files.writeString(assignmentsPath(), content);
        } catch (IOException e) {
            log.error("写入 agent_assignments.json 失败", e);
        }
    }
}
