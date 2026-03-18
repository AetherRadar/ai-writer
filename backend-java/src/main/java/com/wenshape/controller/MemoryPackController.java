package com.wenshape.controller;

import com.wenshape.storage.DraftStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 记忆包控制器 - 章节记忆包状态
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class MemoryPackController {
    
    private final DraftStorage draftStorage;
    
    /**
     * 获取章节记忆包状态
     */
    @GetMapping("/projects/{projectId}/memory-pack/{chapter}")
    public Map<String, Object> getMemoryPackStatus(
            @PathVariable String projectId,
            @PathVariable String chapter) {
        
        Map<String, Object> pack = readPack(projectId, chapter);
        return buildStatus(chapter, pack);
    }
    
    // ========== 私有方法 ==========
    
    private Map<String, Object> readPack(String projectId, String chapter) {
        // Python 版存储在 memory_packs/{chapter}.json
        Path packPath = draftStorage.getProjectPath(projectId)
                .resolve("memory_packs").resolve(chapter + ".json");
        
        if (!Files.exists(packPath)) {
            // 回退：尝试 YAML 格式
            Path yamlPath = draftStorage.getProjectPath(projectId)
                    .resolve("drafts").resolve(chapter).resolve("memory_pack.yaml");
            if (!Files.exists(yamlPath)) {
                return null;
            }
            try {
                return draftStorage.readYamlAsMap(yamlPath);
            } catch (IOException e) {
                log.error("读取记忆包失败: {}", yamlPath, e);
                return null;
            }
        }
        
        try {
            String content = Files.readString(packPath);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(content, Map.class);
            return data;
        } catch (IOException e) {
            log.error("读取记忆包失败: {}", packPath, e);
            return null;
        }
    }
    
    private Map<String, Object> buildStatus(String chapter, Map<String, Object> pack) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("chapter", chapter);
        
        if (pack == null) {
            status.put("exists", false);
            status.put("summary", null);
            status.put("facts_count", 0);
            status.put("scene_brief", null);
            status.put("updated_at", null);
            return status;
        }
        
        status.put("exists", true);
        status.put("summary", pack.get("summary"));
        
        Object facts = pack.get("facts");
        int factsCount = 0;
        if (facts instanceof List<?> list) {
            factsCount = list.size();
        }
        status.put("facts_count", factsCount);
        
        status.put("scene_brief", pack.get("scene_brief"));
        status.put("updated_at", pack.get("updated_at"));
        
        return status;
    }
}
