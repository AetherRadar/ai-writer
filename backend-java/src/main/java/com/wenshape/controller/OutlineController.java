package com.wenshape.controller;

import com.wenshape.llm.LlmGateway;
import com.wenshape.model.entity.ChapterSummary;
import com.wenshape.model.entity.Volume;
import com.wenshape.storage.DraftStorage;
import com.wenshape.storage.VolumeStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/**
 * 大纲控制器 - 独立大纲管理（YAML 存储，与 Python 后端对齐）
 */
@Slf4j
@RestController
@RequestMapping("/projects/{projectId}/outline")
@RequiredArgsConstructor
public class OutlineController {

    private final DraftStorage draftStorage;
    private final VolumeStorage volumeStorage;
    private final LlmGateway llmGateway;

    private static final Pattern CHAPTER_ID_PATTERN = Pattern.compile("V(\\d+)C(\\d+)", Pattern.CASE_INSENSITIVE);

    // ========== GET 全量大纲 ==========

    @GetMapping
    public Map<String, Object> getProjectOutline(@PathVariable String projectId) {
        Map<String, Object> master = readOutlineYaml(projectId, "master.yaml");

        // volumes
        List<Volume> volumes = volumeStorage.listVolumes(projectId);
        List<Map<String, Object>> volumeItems = new ArrayList<>();
        for (Volume vol : volumes) {
            Map<String, Object> raw = readOutlineYaml(projectId, "volumes/" + vol.getId() + ".yaml");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("volume_id", vol.getId());
            item.put("title", vol.getTitle() != null ? vol.getTitle() : "");
            item.put("content", raw.get("content"));
            item.put("updated_at", raw.get("updated_at"));
            volumeItems.add(item);
        }

        // chapters
        List<String> chapters = draftStorage.listChapters(projectId);
        List<ChapterSummary> summaries = draftStorage.listChapterSummaries(projectId, null);
        Map<String, String> titleMap = new HashMap<>();
        Map<String, String> volMap = new HashMap<>();
        for (ChapterSummary s : summaries) {
            String cid = canonicalize(s.getChapter());
            titleMap.put(cid, s.getTitle() != null ? s.getTitle() : "");
            volMap.put(cid, s.getVolumeId() != null ? s.getVolumeId() : extractVolumeId(cid));
        }

        List<Map<String, Object>> chapterItems = new ArrayList<>();
        for (String ch : chapters) {
            String cid = canonicalize(ch);
            Map<String, Object> raw = readOutlineYaml(projectId, "chapters/" + cid + ".yaml");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chapter_id", cid);
            item.put("volume_id", volMap.getOrDefault(cid, extractVolumeId(cid)));
            item.put("title", titleMap.getOrDefault(cid, ""));
            item.put("content", raw.get("content"));
            item.put("updated_at", raw.get("updated_at"));
            chapterItems.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("master", master);
        result.put("volumes", volumeItems);
        result.put("chapters", chapterItems);
        return result;
    }

    // ========== 保存 ==========

    @PutMapping("/master")
    public Map<String, Object> saveMasterOutline(@PathVariable String projectId,
                                                  @RequestBody Map<String, Object> body) throws IOException {
        String content = (String) body.getOrDefault("content", "");
        return writeOutlineYaml(projectId, "master.yaml", content);
    }

    @PutMapping("/volumes/{volumeId}")
    public Map<String, Object> saveVolumeOutline(@PathVariable String projectId,
                                                  @PathVariable String volumeId,
                                                  @RequestBody Map<String, Object> body) throws IOException {
        String content = (String) body.getOrDefault("content", "");
        Map<String, Object> saved = writeOutlineYaml(projectId, "volumes/" + volumeId + ".yaml", content);
        saved.put("volume_id", volumeId);
        return saved;
    }

    @PutMapping("/chapters/{chapterId}")
    public Map<String, Object> saveChapterOutline(@PathVariable String projectId,
                                                   @PathVariable String chapterId,
                                                   @RequestBody Map<String, Object> body) throws IOException {
        String cid = canonicalize(chapterId);
        String content = (String) body.getOrDefault("content", "");
        Map<String, Object> saved = writeOutlineYaml(projectId, "chapters/" + cid + ".yaml", content);
        saved.put("chapter_id", cid);
        return saved;
    }

    // ========== LLM 生成大纲 ==========

    /**
     * AI 生成章节大纲
     * POST /projects/{projectId}/outline/generate
     */
    @PostMapping("/generate")
    public Map<String, Object> generateOutline(@PathVariable String projectId,
                                                @RequestBody Map<String, Object> body) {
        String chapterId = (String) body.getOrDefault("chapter_id", "");
        String instruction = (String) body.getOrDefault("instruction", "");

        if (chapterId.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "chapter_id is required");
        }

        String cid = canonicalize(chapterId);

        // 读取已有大纲作为参考
        Map<String, Object> masterOutline = readOutlineYaml(projectId, "master.yaml");
        String masterContent = (String) masterOutline.getOrDefault("content", "");

        String systemPrompt = "你是一位专业的小说策划助手，擅长为章节生成详细的写作大纲。";
        String userPrompt = String.format("""
                请为章节 %s 生成详细的写作大纲。
                
                %s%s
                
                要求：
                1. 大纲要具体可执行，包含主要场景和情节节点
                2. 标注每个场景的目的（推进情节/塑造人物/铺垫伏笔等）
                3. 字数控制在 300-500 字
                
                直接输出大纲内容，不要加额外说明。
                """,
                cid,
                masterContent.isBlank() ? "" : "总大纲参考：\n" + masterContent.substring(0, Math.min(2000, masterContent.length())) + "\n\n",
                instruction.isBlank() ? "" : "创作指令：" + instruction);

        var resp = llmGateway.chat(
                List.of(Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)),
                llmGateway.getProviderForAgent("writer"), null, 2000);
        String generated = resp.getContent().trim();

        return Map.of(
                "chapter_id", cid,
                "content", generated,
                "success", true
        );
    }

    // ========== 私有方法 ==========

    private Path outlineDir(String projectId) {
        return draftStorage.getProjectPath(projectId).resolve("outline");
    }

    /**
     * 读取大纲 YAML 文件，返回 {content, updated_at}
     */
    private Map<String, Object> readOutlineYaml(String projectId, String subPath) {
        Path path = outlineDir(projectId).resolve(subPath);
        if (Files.exists(path)) {
            try {
                Map<String, Object> data = draftStorage.readYamlAsMap(path);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("content", data.getOrDefault("content", "").toString());
                Object updatedAt = data.get("updated_at");
                result.put("updated_at", updatedAt != null ? updatedAt.toString() : null);
                return result;
            } catch (IOException e) {
                log.error("读取大纲失败: {}", path, e);
            }
        }
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("content", "");
        empty.put("updated_at", null);
        return empty;
    }

    /**
     * 写入大纲 YAML 文件
     */
    private Map<String, Object> writeOutlineYaml(String projectId, String subPath, String content) throws IOException {
        Path path = outlineDir(projectId).resolve(subPath);

        if (content == null || content.isBlank()) {
            // 空内容 = 删除
            Files.deleteIfExists(path);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", "");
            result.put("updated_at", null);
            return result;
        }

        String now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", content);
        payload.put("updated_at", now);

        draftStorage.writeYaml(path, payload);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content);
        result.put("updated_at", now);
        return result;
    }

    private String canonicalize(String chapterId) {
        if (chapterId == null || chapterId.isBlank()) return "";
        String upper = chapterId.trim().toUpperCase();
        Matcher m = CHAPTER_ID_PATTERN.matcher(upper);
        if (m.matches()) {
            return "V" + Integer.parseInt(m.group(1)) + "C" + Integer.parseInt(m.group(2));
        }
        return chapterId.trim();
    }

    private String extractVolumeId(String chapterId) {
        Matcher m = CHAPTER_ID_PATTERN.matcher(chapterId);
        if (m.matches()) return "V" + m.group(1);
        return "V1";
    }
}
