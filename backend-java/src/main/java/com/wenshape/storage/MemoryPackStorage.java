package com.wenshape.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Memory Pack 存储（对齐 Python MemoryPackStorage）
 * 存储路径：data/{projectId}/memory_packs/{chapter}.yaml
 */
@Slf4j
@Component
public class MemoryPackStorage extends BaseStorage {

    /**
     * 读取 memory pack
     */
    public Map<String, Object> readPack(String projectId, String chapter) {
        Path filePath = getPackPath(projectId, chapter);
        if (!Files.exists(filePath)) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> pack = readYaml(filePath, Map.class);
            return pack;
        } catch (IOException e) {
            log.warn("readPack 失败: {}", filePath, e);
            return null;
        }
    }

    /**
     * 写入 memory pack
     */
    public void writePack(String projectId, String chapter, Map<String, Object> pack) throws IOException {
        Path filePath = getPackPath(projectId, chapter);
        writeYaml(filePath, pack);
    }

    /**
     * 删除 memory pack
     */
    public boolean deletePack(String projectId, String chapter) {
        Path filePath = getPackPath(projectId, chapter);
        try {
            return Files.deleteIfExists(filePath);
        } catch (IOException e) {
            log.warn("deletePack 失败: {}", filePath, e);
            return false;
        }
    }

    private Path getPackPath(String projectId, String chapter) {
        // 规范化章节 ID 作为文件名
        String normalized = normalizeChapterId(chapter);
        return getProjectPath(projectId).resolve("memory_packs").resolve(normalized + ".yaml");
    }

    private String normalizeChapterId(String chapter) {
        if (chapter == null || chapter.isBlank()) return "unknown";
        String upper = chapter.trim().toUpperCase();
        int vIdx = upper.indexOf('V');
        int cIdx = upper.indexOf('C');
        if (vIdx >= 0 && cIdx > vIdx) {
            try {
                int vol = Integer.parseInt(upper.substring(vIdx + 1, cIdx));
                int ch = Integer.parseInt(upper.substring(cIdx + 1));
                return String.format("V%dC%d", vol, ch);
            } catch (NumberFormatException ignored) {}
        }
        return chapter.trim();
    }
}
