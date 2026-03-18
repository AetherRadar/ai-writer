package com.wenshape.controller;

import com.wenshape.storage.CardStorage;
import com.wenshape.storage.DraftStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 章节绑定控制器 - 关联角色/世界观卡片到章节文本
 */
@Slf4j
@RestController
@RequestMapping("/projects/{projectId}/bindings")
@RequiredArgsConstructor
public class BindingsController {
    
    private final CardStorage cardStorage;
    private final DraftStorage draftStorage;
    
    private static final Pattern CHAPTER_ID_PATTERN = Pattern.compile("V(\\d+)C(\\d+)");
    
    /**
     * 获取章节绑定
     */
    @GetMapping("/{chapter}")
    public Map<String, Object> getBindings(
            @PathVariable String projectId,
            @PathVariable String chapter) {
        
        Map<String, Object> binding = readBindings(projectId, chapter);
        return Map.of("binding", binding != null ? binding : Collections.emptyMap());
    }
    
    /**
     * 重建单个章节绑定
     */
    @PostMapping("/{chapter}/rebuild")
    public Map<String, Object> rebuildBindings(
            @PathVariable String projectId,
            @PathVariable String chapter) throws IOException {
        
        Map<String, Object> binding = buildBindings(projectId, chapter, true);
        return Map.of("success", true, "binding", binding);
    }
    
    /**
     * 供 DraftController 等内部调用：保存草稿后重建绑定（对齐 Python chapter_binding_service.build_bindings）
     */
    public Map<String, Object> rebuildBindingsForChapter(String projectId, String chapter) {
        try {
            return buildBindings(projectId, chapter, true);
        } catch (IOException e) {
            log.warn("重建绑定失败: {}/{}", projectId, chapter, e);
            return Collections.emptyMap();
        }
    }

    /**
     * 批量重建绑定
     */
    @PostMapping("/rebuild-batch")
    public Map<String, Object> rebuildBindingsBatch(
            @PathVariable String projectId,
            @RequestBody(required = false) Map<String, Object> request) throws IOException {
        
        @SuppressWarnings("unchecked")
        List<String> chapters = request != null ? (List<String>) request.get("chapters") : null;
        
        if (chapters == null || chapters.isEmpty()) {
            chapters = draftStorage.listChapters(projectId);
        }
        
        List<Map<String, Object>> results = new ArrayList<>();
        for (String chapter : chapters) {
            try {
                Map<String, Object> binding = buildBindings(projectId, chapter, true);
                results.add(Map.of(
                        "chapter", chapter,
                        "success", true,
                        "binding", binding
                ));
            } catch (Exception e) {
                log.error("重建绑定失败: {}/{}", projectId, chapter, e);
                results.add(Map.of(
                        "chapter", chapter,
                        "success", false,
                        "error", e.getMessage()
                ));
            }
        }
        
        return Map.of("success", true, "results", results);
    }
    
    // ========== 私有方法 ==========
    
    private Map<String, Object> readBindings(String projectId, String chapter) {
        String canonical = canonicalizeChapterId(chapter);
        Path bindingPath = getBindingPath(projectId, canonical);
        
        if (bindingPath == null || !Files.exists(bindingPath)) {
            return null;
        }
        
        try {
            return draftStorage.readYamlAsMap(bindingPath);
        } catch (IOException e) {
            log.error("读取绑定失败: {}", bindingPath, e);
            return null;
        }
    }
    
    private Map<String, Object> buildBindings(String projectId, String chapter, boolean force) throws IOException {
        String canonical = canonicalizeChapterId(chapter);
        Path bindingPath = getBindingPath(projectId, canonical);
        
        // 如果不强制且已存在，直接返回
        if (!force && bindingPath != null && Files.exists(bindingPath)) {
            return readBindings(projectId, chapter);
        }
        
        // 获取章节内容
        Optional<String> draftOpt = draftStorage.getFinalDraft(projectId, chapter);
        if (draftOpt.isEmpty()) {
            return Collections.emptyMap();
        }
        
        String content = draftOpt.get();
        
        // 匹配角色卡
        List<String> characterNames = cardStorage.listCharacterCards(projectId);
        List<Map<String, Object>> matchedCharacters = new ArrayList<>();
        
        for (String name : characterNames) {
            var cardOpt = cardStorage.getCharacterCard(projectId, name);
            if (cardOpt.isEmpty()) continue;
            
            var card = cardOpt.get();
            List<String> searchTerms = new ArrayList<>();
            searchTerms.add(name);
            if (card.getAliases() != null) {
                searchTerms.addAll(card.getAliases());
            }
            
            int mentions = countMentions(content, searchTerms);
            if (mentions > 0) {
                matchedCharacters.add(Map.of(
                        "name", name,
                        "mentions", mentions,
                        "stars", card.getStars() != null ? card.getStars() : 1
                ));
            }
        }
        
        // 匹配世界观卡
        List<String> worldNames = cardStorage.listWorldCards(projectId);
        List<Map<String, Object>> matchedWorld = new ArrayList<>();
        
        for (String name : worldNames) {
            var cardOpt = cardStorage.getWorldCard(projectId, name);
            if (cardOpt.isEmpty()) continue;
            
            var card = cardOpt.get();
            List<String> searchTerms = new ArrayList<>();
            searchTerms.add(name);
            if (card.getAliases() != null) {
                searchTerms.addAll(card.getAliases());
            }
            
            int mentions = countMentions(content, searchTerms);
            if (mentions > 0) {
                matchedWorld.add(Map.of(
                        "name", name,
                        "mentions", mentions,
                        "category", card.getCategory() != null ? card.getCategory() : "other"
                ));
            }
        }
        
        // 按提及次数排序
        matchedCharacters.sort((a, b) -> Integer.compare((int) b.get("mentions"), (int) a.get("mentions")));
        matchedWorld.sort((a, b) -> Integer.compare((int) b.get("mentions"), (int) a.get("mentions")));
        
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("chapter", canonical);
        binding.put("characters", matchedCharacters);
        binding.put("world", matchedWorld);
        binding.put("updated_at", System.currentTimeMillis() / 1000);
        
        // 保存绑定
        Path savePath = draftStorage.getProjectPath(projectId)
                .resolve("drafts").resolve(canonical).resolve("bindings.yaml");
        draftStorage.writeYaml(savePath, binding);
        
        return binding;
    }
    
    private int countMentions(String content, List<String> terms) {
        int count = 0;
        String lowerContent = content.toLowerCase();
        
        for (String term : terms) {
            if (term == null || term.isBlank()) continue;
            String lowerTerm = term.toLowerCase();
            int idx = 0;
            while ((idx = lowerContent.indexOf(lowerTerm, idx)) != -1) {
                count++;
                idx += lowerTerm.length();
            }
        }
        
        return count;
    }
    
    private Path getBindingPath(String projectId, String chapter) {
        return draftStorage.getProjectPath(projectId)
                .resolve("drafts").resolve(chapter).resolve("bindings.yaml");
    }
    
    private String canonicalizeChapterId(String chapterId) {
        if (chapterId == null || chapterId.isBlank()) {
            return "";
        }
        
        String normalized = chapterId.trim().toUpperCase();
        Matcher matcher = CHAPTER_ID_PATTERN.matcher(normalized);
        if (matcher.matches()) {
            int vol = Integer.parseInt(matcher.group(1));
            int ch = Integer.parseInt(matcher.group(2));
            return String.format("V%dC%d", vol, ch);
        }
        
        return chapterId.trim();
    }
}
