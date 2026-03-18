package com.wenshape.controller;

import com.wenshape.model.entity.ChapterSummary;
import com.wenshape.model.entity.Fact;
import com.wenshape.model.entity.Volume;
import com.wenshape.storage.CanonStorage;
import com.wenshape.storage.DraftStorage;
import com.wenshape.storage.VolumeStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 事实树控制器 - 分卷/章节/事实的树形结构
 */
@Slf4j
@RestController
@RequestMapping("/projects/{projectId}/facts")
@RequiredArgsConstructor
public class FactsController {
    
    private final CanonStorage canonStorage;
    private final DraftStorage draftStorage;
    private final VolumeStorage volumeStorage;
    
    private static final Pattern CHAPTER_ID_PATTERN = Pattern.compile("V(\\d+)C(\\d+)", Pattern.CASE_INSENSITIVE);
    
    /**
     * 获取事实树
     */
    @GetMapping("/tree")
    public Map<String, Object> getFactsTree(@PathVariable String projectId) {
        // 加载卷信息
        List<Volume> volumes = volumeStorage.listVolumes(projectId);
        Map<String, Map<String, Object>> volumeMap = new LinkedHashMap<>();
        
        for (Volume volume : volumes) {
            volumeMap.put(volume.getId(), Map.of(
                    "id", volume.getId(),
                    "title", volume.getTitle() != null ? volume.getTitle() : "",
                    "summary", volume.getSummary() != null ? volume.getSummary() : "",
                    "chapters", new ArrayList<Map<String, Object>>()
            ));
        }
        
        // 加载章节摘要
        List<ChapterSummary> summaries = draftStorage.listChapterSummaries(projectId, null);
        Map<String, ChapterSummary> summaryMap = new HashMap<>();
        for (ChapterSummary summary : summaries) {
            String canonical = canonicalizeChapterId(summary.getChapter());
            summaryMap.put(canonical, summary);
        }
        
        // 加载事实
        List<Fact> facts = canonStorage.listFacts(projectId);
        Map<String, List<Map<String, Object>>> factsByChapter = new HashMap<>();
        
        for (Fact fact : facts) {
            String chapterId = canonicalizeChapterId(
                    fact.getIntroducedIn() != null ? fact.getIntroducedIn() : 
                    fact.getSource() != null ? fact.getSource() : "V1C0"
            );
            
            factsByChapter.computeIfAbsent(chapterId, k -> new ArrayList<>()).add(Map.of(
                    "id", fact.getId() != null ? fact.getId() : "",
                    "display_id", formatDisplayId(fact.getId(), factsByChapter.get(chapterId).size()),
                    "title", fact.getTitle() != null ? fact.getTitle() : deriveTitle(fact.getStatement()),
                    "content", fact.getContent() != null ? fact.getContent() : fact.getStatement(),
                    "statement", fact.getStatement() != null ? fact.getStatement() : "",
                    "source", fact.getSource() != null ? fact.getSource() : chapterId,
                    "introduced_in", chapterId,
                    "confidence", fact.getConfidence() != null ? fact.getConfidence() : 1.0,
                    "origin", "canon"
            ));
        }
        
        // 收集所有章节 ID
        Set<String> chapterIds = new HashSet<>();
        chapterIds.addAll(summaryMap.keySet());
        chapterIds.addAll(factsByChapter.keySet());
        
        // 按卷分组章节
        Map<String, List<String>> chaptersByVolume = new HashMap<>();
        for (String chapterId : chapterIds) {
            String volumeId = extractVolumeId(chapterId);
            chaptersByVolume.computeIfAbsent(volumeId, k -> new ArrayList<>()).add(chapterId);
            
            // 确保卷存在
            if (!volumeMap.containsKey(volumeId)) {
                volumeMap.put(volumeId, new LinkedHashMap<>(Map.of(
                        "id", volumeId,
                        "title", "Volume " + volumeId.substring(1),
                        "summary", "",
                        "chapters", new ArrayList<Map<String, Object>>()
                )));
            }
        }
        
        // 构建章节数据
        for (Map.Entry<String, List<String>> entry : chaptersByVolume.entrySet()) {
            String volumeId = entry.getKey();
            List<String> chapters = entry.getValue();
            
            // 排序章节
            chapters.sort(this::compareChapterIds);
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> volumeChapters = 
                    (List<Map<String, Object>>) ((Map<String, Object>) volumeMap.get(volumeId)).get("chapters");
            
            for (String chapterId : chapters) {
                ChapterSummary summary = summaryMap.get(chapterId);
                String chapterTitle = summary != null && summary.getTitle() != null ? 
                        summary.getTitle() : chapterId;
                String chapterSummary = summary != null && summary.getBriefSummary() != null ? 
                        summary.getBriefSummary() : "";
                
                List<Map<String, Object>> chapterFacts = factsByChapter.getOrDefault(chapterId, new ArrayList<>());
                
                // 添加摘要中的事实
                if (summary != null && summary.getNewFacts() != null) {
                    Set<String> canonStatements = new HashSet<>();
                    for (Map<String, Object> fact : chapterFacts) {
                        canonStatements.add(normalizeText((String) fact.get("statement")));
                    }
                    
                    int idx = 0;
                    for (Object item : summary.getNewFacts()) {
                        Map<String, Object> summaryFact = normalizeSummaryFact(chapterId, item, idx);
                        String statement = normalizeText((String) summaryFact.get("statement"));
                        
                        if (!canonStatements.contains(statement)) {
                            chapterFacts.add(summaryFact);
                        }
                        idx++;
                    }
                }
                
                volumeChapters.add(Map.of(
                        "id", chapterId,
                        "title", chapterTitle,
                        "summary", chapterSummary,
                        "facts", chapterFacts
                ));
            }
        }
        
        // 排序卷
        List<Map<String, Object>> sortedVolumes = volumeMap.values().stream()
                .sorted((a, b) -> compareVolumeIds((String) a.get("id"), (String) b.get("id")))
                .toList();
        
        return Map.of("volumes", sortedVolumes);
    }
    
    // ========== 私有方法 ==========
    
    private String canonicalizeChapterId(String chapterId) {
        if (chapterId == null || chapterId.isBlank()) {
            return "V1C0";
        }
        
        String normalized = chapterId.trim().toUpperCase();
        Matcher matcher = CHAPTER_ID_PATTERN.matcher(normalized);
        if (matcher.matches()) {
            int vol = Integer.parseInt(matcher.group(1));
            int ch = Integer.parseInt(matcher.group(2));
            return String.format("V%dC%d", vol, ch);
        }
        
        // 尝试其他格式
        if (normalized.startsWith("C")) {
            return "V1" + normalized;
        }
        
        return "V1C0";
    }
    
    private String extractVolumeId(String chapterId) {
        Matcher matcher = CHAPTER_ID_PATTERN.matcher(chapterId);
        if (matcher.matches()) {
            return "V" + matcher.group(1);
        }
        return "V1";
    }
    
    private int compareChapterIds(String a, String b) {
        Matcher ma = CHAPTER_ID_PATTERN.matcher(a);
        Matcher mb = CHAPTER_ID_PATTERN.matcher(b);
        
        if (ma.matches() && mb.matches()) {
            int volA = Integer.parseInt(ma.group(1));
            int volB = Integer.parseInt(mb.group(1));
            if (volA != volB) return Integer.compare(volA, volB);
            
            int chA = Integer.parseInt(ma.group(2));
            int chB = Integer.parseInt(mb.group(2));
            return Integer.compare(chA, chB);
        }
        
        return a.compareTo(b);
    }
    
    private int compareVolumeIds(String a, String b) {
        try {
            int volA = Integer.parseInt(a.substring(1));
            int volB = Integer.parseInt(b.substring(1));
            return Integer.compare(volA, volB);
        } catch (Exception e) {
            return a.compareTo(b);
        }
    }
    
    private String formatDisplayId(String factId, int index) {
        if (factId == null || factId.isBlank()) {
            return String.format("F%02d", index + 1);
        }
        // 如果是 UUID 或长 hex，使用序号
        if (factId.matches("[a-fA-F0-9]{10,}") || factId.matches("[a-fA-F0-9-]{16,}")) {
            return String.format("F%02d", index + 1);
        }
        return factId;
    }
    
    private String deriveTitle(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String cleaned = text.trim().replace("\n", " ");
        if (cleaned.length() <= 24) {
            return cleaned;
        }
        return cleaned.substring(0, 24).trim() + "...";
    }
    
    private String normalizeText(String text) {
        if (text == null) return "";
        return text.replaceAll("\\s+", "").toLowerCase();
    }
    
    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeSummaryFact(String chapterId, Object item, int index) {
        String statement;
        String title;
        String content;
        
        if (item instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) item;
            statement = getStringOrDefault(map, "statement", 
                    getStringOrDefault(map, "content", 
                    getStringOrDefault(map, "text", "")));
            title = getStringOrDefault(map, "title", 
                    getStringOrDefault(map, "name", deriveTitle(statement)));
            content = getStringOrDefault(map, "content", statement);
        } else {
            statement = item != null ? item.toString() : "";
            title = deriveTitle(statement);
            content = statement;
        }
        
        return Map.of(
                "id", String.format("S%s-%d", chapterId, index + 1),
                "display_id", String.format("S%02d", index + 1),
                "title", title,
                "content", content,
                "statement", statement,
                "source", chapterId,
                "introduced_in", chapterId,
                "confidence", 1.0,
                "origin", "summary"
        );
    }
    
    private String getStringOrDefault(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }
}
