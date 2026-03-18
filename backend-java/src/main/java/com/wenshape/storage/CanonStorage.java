package com.wenshape.storage;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wenshape.model.entity.Fact;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
/**
 * 事实表存储
 */
@Slf4j
@Component
public class CanonStorage extends BaseStorage {
    
    public List<Fact> listFacts(String projectId) {
        Path canonDir = getProjectPath(projectId).resolve("canon");
        if (!Files.exists(canonDir)) {
            return new ArrayList<>();
        }
        
        List<Fact> facts = new ArrayList<>();
        
        try (Stream<Path> paths = Files.list(canonDir)) {
            for (Path p : paths.filter(f -> f.toString().endsWith(".yaml")).toList()) {
                try {
                    Fact fact = readYaml(p, Fact.class);
                    if (fact.getId() == null || fact.getId().isEmpty()) {
                        fact.setId(p.getFileName().toString().replace(".yaml", ""));
                    }
                    facts.add(fact);
                } catch (IOException e) {
                    log.error("读取事实失败: {}", p, e);
                }
            }
        } catch (IOException e) {
            log.error("列出事实失败: {}", canonDir, e);
        }
        
        return facts;
    }
    
    public Optional<Fact> getFact(String projectId, String factId) {
        Path filePath = getProjectPath(projectId).resolve("canon").resolve(factId + ".yaml");
        
        if (!Files.exists(filePath)) {
            return Optional.empty();
        }
        
        try {
            Fact fact = readYaml(filePath, Fact.class);
            if (fact.getId() == null || fact.getId().isEmpty()) {
                fact.setId(factId);
            }
            return Optional.of(fact);
        } catch (IOException e) {
            log.error("读取事实失败: {}", filePath, e);
            return Optional.empty();
        }
    }
    
    public Fact saveFact(String projectId, Fact fact) throws IOException {
        if (fact.getId() == null || fact.getId().isEmpty()) {
            fact.setId(generateFactId(projectId));
        }
        
        Path filePath = getProjectPath(projectId).resolve("canon").resolve(fact.getId() + ".yaml");
        writeYaml(filePath, fact);
        return fact;
    }
    
    public boolean deleteFact(String projectId, String factId) {
        Path filePath = getProjectPath(projectId).resolve("canon").resolve(factId + ".yaml");
        try {
            return Files.deleteIfExists(filePath);
        } catch (IOException e) {
            log.error("删除事实失败: {}", filePath, e);
            return false;
        }
    }
    
    /**
     * 按章节批量删除 facts（overwrite 时使用，对齐 Python delete_facts_by_chapter）
     */
    public int deleteFactsByChapter(String projectId, String chapter) {
        List<Fact> facts = listFacts(projectId);
        int deleted = 0;
        for (Fact fact : facts) {
            if (chapter.equals(fact.getIntroducedIn())) {
                if (deleteFact(projectId, fact.getId())) {
                    deleted++;
                }
            }
        }
        return deleted;
    }

    /**
     * 保存时间线事件（对齐 Python add_timeline_event）
     * 存储为 timeline/ 目录下的 YAML 文件
     */
    public void saveTimelineEvent(String projectId, Map<String, Object> event) throws IOException {
        Path dir = getProjectPath(projectId).resolve("timeline");
        Files.createDirectories(dir);

        String id = (String) event.get("id");
        if (id == null || id.isBlank()) {
            id = generateTimelineId(dir);
            event = new LinkedHashMap<>(event);
            event.put("id", id);
        }
        Path filePath = dir.resolve(id + ".yaml");
        writeYaml(filePath, event);
    }

    /**
     * 保存角色状态（对齐 Python update_character_state）
     * 以角色名为 key，存储为 character_states/ 目录下的 YAML 文件
     */
    public void saveCharacterState(String projectId, Map<String, Object> state) throws IOException {
        Path dir = getProjectPath(projectId).resolve("character_states");
        Files.createDirectories(dir);

        String character = (String) state.get("character");
        if (character == null || character.isBlank()) {
            log.warn("saveCharacterState: character name is blank, skipping");
            return;
        }
        // 用角色名做文件名（简单 sanitize）
        String filename = character.replaceAll("[^\\w\\u4e00-\\u9fff]", "_");
        Path filePath = dir.resolve(filename + ".yaml");
        writeYaml(filePath, state);
    }

    public List<Map<String, Object>> listTimelineEvents(String projectId) {
        Path dir = getProjectPath(projectId).resolve("timeline");
        return readYamlMaps(dir);
    }

    public List<Map<String, Object>> listTimelineEventsByChapter(String projectId, String chapter) {
        String canonical = chapter != null ? chapter.trim() : "";
        return listTimelineEvents(projectId).stream()
                .filter(item -> canonical.equals(item.getOrDefault("source", "")))
                .toList();
    }

    public List<Map<String, Object>> listCharacterStates(String projectId) {
        Path dir = getProjectPath(projectId).resolve("character_states");
        return readYamlMaps(dir);
    }

    public Optional<Map<String, Object>> getCharacterState(String projectId, String characterName) {
        if (characterName == null || characterName.isBlank()) {
            return Optional.empty();
        }
        String filename = characterName.replaceAll("[^\\w\\u4e00-\\u9fff]", "_");
        Path filePath = getProjectPath(projectId).resolve("character_states").resolve(filename + ".yaml");
        if (!Files.exists(filePath)) {
            return Optional.empty();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> state = readYaml(filePath, Map.class);
            return Optional.ofNullable(state);
        } catch (IOException e) {
            log.error("读取角色状态失败: {}", filePath, e);
            return Optional.empty();
        }
    }

    private String generateFactId(String projectId) {
        List<Fact> facts = listFacts(projectId);
        int maxNum = 0;
        
        for (Fact fact : facts) {
            String id = fact.getId();
            if (id != null && id.startsWith("F")) {
                try {
                    int num = Integer.parseInt(id.substring(1));
                    maxNum = Math.max(maxNum, num);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        
        return String.format("F%03d", maxNum + 1);
    }

    private String generateTimelineId(Path dir) {
        int maxNum = 0;
        try (Stream<Path> paths = Files.list(dir)) {
            for (Path p : paths.filter(f -> f.toString().endsWith(".yaml")).toList()) {
                String name = p.getFileName().toString().replace(".yaml", "");
                if (name.startsWith("T")) {
                    try {
                        int num = Integer.parseInt(name.substring(1));
                        maxNum = Math.max(maxNum, num);
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (IOException ignored) {}
        return String.format("T%03d", maxNum + 1);
    }

    /**
     * 冲突检测（对齐 Python canon_storage.detect_conflicts）
     * 简化实现：检查新 facts 是否与已有 facts 有矛盾关键词
     * 存储路径：data/{projectId}/drafts/{chapter}/conflict_report.yaml
     */
    public Map<String, Object> detectConflicts(String projectId, String chapter,
                                                List<Map<String, Object>> newFacts,
                                                List<Map<String, Object>> newTimelineEvents,
                                                List<Map<String, Object>> newCharacterStates) {
        List<Map<String, Object>> conflicts = new ArrayList<>();

        try {
            List<Fact> existingFacts = listFacts(projectId);
            // 简化冲突检测：检查新 facts 的关键词是否与已有 facts 有矛盾
            for (Map<String, Object> newFact : newFacts) {
                String newStmt = (String) newFact.getOrDefault("statement", "");
                if (newStmt.isBlank()) continue;

                for (Fact existing : existingFacts) {
                    String existingStmt = existing.getStatement() != null ? existing.getStatement() : existing.getContent();
                    if (existingStmt == null || existingStmt.isBlank()) continue;
                    // 跳过同章节的 facts（不与自己冲突）
                    if (chapter.equals(existing.getIntroducedIn())) continue;

                    // 简单矛盾检测：包含否定词且共享关键词
                    if (hasPotentialConflict(newStmt, existingStmt)) {
                        Map<String, Object> conflict = new LinkedHashMap<>();
                        conflict.put("type", "fact_conflict");
                        conflict.put("new_fact", newStmt);
                        conflict.put("existing_fact", existingStmt);
                        conflict.put("existing_chapter", existing.getIntroducedIn());
                        conflict.put("severity", "low");
                        conflicts.add(conflict);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("detectConflicts 失败: {}", e.getMessage());
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("chapter", chapter);
        report.put("conflicts", conflicts);
        report.put("conflict_count", conflicts.size());
        report.put("has_conflicts", !conflicts.isEmpty());
        report.put("generated_at", java.time.Instant.now().toString());
        return report;
    }

    private boolean hasPotentialConflict(String stmt1, String stmt2) {
        // 提取共享关键词（长度>=2的中文词或英文词）
        Set<String> words1 = extractKeywords(stmt1);
        Set<String> words2 = extractKeywords(stmt2);
        Set<String> shared = new HashSet<>(words1);
        shared.retainAll(words2);
        if (shared.isEmpty()) return false;

        // 检查是否有否定关系（一个包含否定词，另一个不包含）
        boolean neg1 = containsNegation(stmt1);
        boolean neg2 = containsNegation(stmt2);
        return neg1 != neg2;
    }

    private Set<String> extractKeywords(String text) {
        Set<String> keywords = new HashSet<>();
        // 提取中文词（2-4字）
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[\u4e00-\u9fff]{2,4}").matcher(text);
        while (m.find()) keywords.add(m.group());
        // 提取英文词
        m = java.util.regex.Pattern.compile("[a-zA-Z]{3,}").matcher(text);
        while (m.find()) keywords.add(m.group().toLowerCase());
        return keywords;
    }

    private boolean containsNegation(String text) {
        return text.contains("不") || text.contains("没") || text.contains("无") ||
               text.contains("非") || text.contains("未") || text.contains("否");
    }

    private List<Map<String, Object>> readYamlMaps(Path dir) {
        if (!Files.exists(dir)) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        try (Stream<Path> paths = Files.list(dir)) {
            for (Path p : paths.filter(f -> f.toString().endsWith(".yaml")).toList()) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> item = readYaml(p, Map.class);
                    if (item != null) {
                        items.add(item);
                    }
                } catch (IOException e) {
                    log.error("读取 YAML 数据失败: {}", p, e);
                }
            }
        } catch (IOException e) {
            log.error("列出 YAML 目录失败: {}", dir, e);
        }
        return items;
    }
}
