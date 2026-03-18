package com.wenshape.controller;

import com.wenshape.context.ContextItem;
import com.wenshape.context.ContextSelectEngine;
import com.wenshape.storage.DraftStorage;
import com.wenshape.storage.MemoryPackStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 证据控制器 - 证据搜索
 */
@Slf4j
@RestController
@RequestMapping("/projects/{projectId}/evidence")
@RequiredArgsConstructor
public class EvidenceController {
    
    private final ContextSelectEngine selectEngine;
    private final DraftStorage draftStorage;
    private final MemoryPackStorage memoryPackStorage;
    
    /**
     * 搜索证据
     * POST /projects/{projectId}/evidence/search
     */
    @PostMapping("/search")
    public Map<String, Object> searchEvidence(@PathVariable String projectId,
                                               @RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<String> queries = (List<String>) request.getOrDefault("queries", List.of());
        @SuppressWarnings("unchecked")
        List<String> types = (List<String>) request.getOrDefault("types", List.of("fact", "character", "world", "text_chunk", "memory"));
        int limit = request.get("limit") instanceof Number n ? n.intValue() : 12;
        boolean includeTextChunks = !Boolean.FALSE.equals(request.get("include_text_chunks"));
        @SuppressWarnings("unchecked")
        List<String> seedEntities = (List<String>) request.getOrDefault("seed_entities", List.of());
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Integer>> quotas = (Map<String, Map<String, Integer>>) request.get("quotas");
        @SuppressWarnings("unchecked")
        List<String> textChunkChapters = (List<String>) request.get("text_chunk_chapters");
        @SuppressWarnings("unchecked")
        List<String> excludeChapters = (List<String>) request.get("text_chunk_exclude_chapters");
        boolean rebuild = Boolean.TRUE.equals(request.get("rebuild"));
        
        // 合并查询
        List<String> mergedQueries = new ArrayList<>();
        if (queries != null) mergedQueries.addAll(queries);
        if (seedEntities != null) mergedQueries.addAll(seedEntities);
        String combinedQuery = mergedQueries.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining(" "));
        
        // 使用 ContextSelectEngine 检索
        List<String> selectTypes = new ArrayList<>();
        if (types.contains("character")) selectTypes.add("character");
        if (types.contains("world") || types.contains("world_rule") || types.contains("world_entity")) selectTypes.add("world");
        if (types.contains("fact")) selectTypes.add("fact");
        if (types.contains("text_chunk")) selectTypes.add("text_chunk");

        List<ContextItem> items = combinedQuery.isBlank()
                ? List.of()
                : selectEngine.retrievalSelect(projectId, combinedQuery, selectTypes, Math.max(limit * 2, 24));
        
        // 转换为响应格式
        List<Map<String, Object>> resultItems = new ArrayList<>(items.stream()
                .map(this::toEvidenceItem)
                .filter(Objects::nonNull)
                .collect(Collectors.toList()));

        if (includeTextChunks && types.contains("text_chunk") && !combinedQuery.isBlank()) {
            List<Map<String, Object>> textChunks = draftStorage.searchTextChunks(
                    projectId,
                    combinedQuery,
                    limit,
                    textChunkChapters,
                    excludeChapters,
                    rebuild
            );
            for (Map<String, Object> chunk : textChunks) {
                resultItems.add(toChunkEvidenceItem(chunk));
            }
        }

        if (types.contains("memory") && !combinedQuery.isBlank()) {
            resultItems.addAll(searchMemoryEvidence(projectId, mergedQueries, textChunkChapters));
        }

        Map<String, Map<String, Integer>> mergedQuotas = mergeQuotas(types, quotas);
        resultItems = applyTypeQuotas(resultItems, mergedQuotas, limit);
        
        return Map.of(
                "items", resultItems,
                "stats", Map.of(
                        "total", resultItems.size(),
                        "query_count", mergedQueries.size(),
                        "types", countTypes(resultItems),
                        "queries", mergedQueries,
                        "hits", resultItems.size(),
                        "top_sources", extractTopSources(resultItems, 3),
                        "limit", limit,
                        "types_requested", types
                )
        );
    }
    
    /**
     * 重建证据索引
     * POST /projects/{projectId}/evidence/rebuild
     */
    @PostMapping("/rebuild")
    public Map<String, Object> rebuildEvidence(@PathVariable String projectId) {
        log.info("Rebuilding evidence index for project: {}", projectId);
        Map<String, Object> textChunkMeta = draftStorage.rebuildTextChunkIndex(projectId);
        return Map.of(
                "success", true,
                "meta", Map.of(
                        "rebuilt_at", System.currentTimeMillis(),
                        "text_chunks", textChunkMeta
                )
        );
    }

    private Map<String, Integer> countTypes(List<Map<String, Object>> items) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            String type = String.valueOf(item.getOrDefault("type", ""));
            if (type.isBlank()) {
                continue;
            }
            counts.put(type, counts.getOrDefault(type, 0) + 1);
        }
        return counts;
    }

    private Map<String, Object> toEvidenceItem(ContextItem item) {
        if (item == null || item.getType() == null || item.getContent() == null || item.getContent().isBlank()) {
            return null;
        }
        String type = item.getType().getValue();
        if ("character_card".equals(type)) type = "character";
        if ("world_card".equals(type)) type = "world";
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", item.getId());
        map.put("type", type);
        map.put("text", item.getContent());
        map.put("score", item.getRelevanceScore());
        map.put("source", item.getMetadata() != null ? new LinkedHashMap<>(item.getMetadata()) : Map.of());
        return map;
    }

    private Map<String, Object> toChunkEvidenceItem(Map<String, Object> chunk) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "text_chunk:" + chunk.get("chapter") + ":" + chunk.get("chunk_index"));
        item.put("type", "text_chunk");
        item.put("text", chunk.get("text"));
        item.put("score", chunk.get("score"));
        item.put("source", chunk.get("source") instanceof Map<?, ?> source ? castStringObjectMap(source) : Map.of());
        return item;
    }

    private List<Map<String, Object>> searchMemoryEvidence(String projectId, List<String> queries, List<String> chapters) {
        List<String> cleaned = queries == null ? List.of() : queries.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
        if (cleaned.isEmpty()) {
            return List.of();
        }
        List<String> candidates = chapters != null && !chapters.isEmpty()
                ? chapters
                : draftStorage.listChapters(projectId).stream().sorted().skip(Math.max(0, draftStorage.listChapters(projectId).size() - 6L)).toList();
        List<Map<String, Object>> results = new ArrayList<>();
        for (String chapter : candidates) {
            Map<String, Object> pack = memoryPackStorage.readPack(projectId, chapter);
            if (pack == null || pack.isEmpty()) {
                continue;
            }
            for (Map<String, Object> item : extractMemoryItems(chapter, pack)) {
                String text = String.valueOf(item.getOrDefault("text", ""));
                double score = scoreText(cleaned, text);
                if (score <= 0) {
                    continue;
                }
                item.put("score", score);
                results.add(item);
            }
        }
        results.sort((a, b) -> Double.compare(
                ((Number) b.getOrDefault("score", 0.0)).doubleValue(),
                ((Number) a.getOrDefault("score", 0.0)).doubleValue()
        ));
        return results.size() > 8 ? new ArrayList<>(results.subList(0, 8)) : results;
    }

    private List<Map<String, Object>> extractMemoryItems(String chapter, Map<String, Object> pack) {
        List<Map<String, Object>> items = new ArrayList<>();
        Object payloadObj = pack.get("payload");
        if (payloadObj instanceof Map<?, ?> payload) {
            String workingMemory = stringValue(payload, "working_memory", "");
            if (!workingMemory.isBlank()) {
                items.add(memoryItem("memory:" + chapter + ":working_memory", chapter, workingMemory, "working_memory"));
            }
            Object evidencePack = payload.get("evidence_pack");
            if (evidencePack instanceof Map<?, ?> ep && ep.get("items") instanceof List<?> evidenceItems) {
                int count = 0;
                for (Object item : evidenceItems) {
                    if (item instanceof Map<?, ?> ev) {
                        String text = stringValue(ev, "text", "");
                        if (!text.isBlank()) {
                            items.add(memoryItem("memory:" + chapter + ":evidence:" + count, chapter, text, "evidence_pack"));
                            if (++count >= 4) break;
                        }
                    }
                }
            }
        }
        Object digestObj = pack.get("chapter_digest");
        if (digestObj instanceof Map<?, ?> digest) {
            String summary = stringValue(digest, "summary", "");
            if (!summary.isBlank()) {
                items.add(memoryItem("memory:" + chapter + ":summary", chapter, summary, "chapter_digest"));
            }
            String tail = stringValue(digest, "tail_excerpt", "");
            if (!tail.isBlank()) {
                items.add(memoryItem("memory:" + chapter + ":tail", chapter, tail, "tail_excerpt"));
            }
        }
        return items;
    }

    private Map<String, Object> memoryItem(String id, String chapter, String text, String field) {
        return new LinkedHashMap<>(Map.of(
                "id", id,
                "type", "memory",
                "text", text,
                "score", 0.0,
                "source", Map.of("chapter", chapter, "field", field)
        ));
    }

    private double scoreText(List<String> queries, String text) {
        if (text == null || text.isBlank()) {
            return 0.0;
        }
        Set<String> terms = new LinkedHashSet<>();
        for (String query : queries) {
            Matcher matcher = Pattern.compile("[\\p{IsHan}]{2,4}|[A-Za-z0-9_]{2,}").matcher(query.toLowerCase());
            while (matcher.find()) {
                terms.add(matcher.group());
            }
        }
        if (terms.isEmpty()) {
            return 0.0;
        }
        String lower = text.toLowerCase();
        int overlap = 0;
        for (String term : terms) {
            if (lower.contains(term)) {
                overlap++;
            }
        }
        if (overlap == 0) {
            return 0.0;
        }
        return overlap / (double) terms.size();
    }

    private Map<String, Map<String, Integer>> mergeQuotas(List<String> types, Map<String, Map<String, Integer>> custom) {
        Map<String, Map<String, Integer>> defaults = new LinkedHashMap<>();
        defaults.put("fact", quota(3, 8));
        defaults.put("text_chunk", quota(3, 8));
        defaults.put("character", quota(0, 6));
        defaults.put("world", quota(0, 4));
        defaults.put("memory", quota(0, 4));

        Map<String, Map<String, Integer>> merged = new LinkedHashMap<>();
        for (String type : types) {
            if (defaults.containsKey(type)) {
                merged.put(type, new LinkedHashMap<>(defaults.get(type)));
            }
        }
        if (custom != null) {
            for (Map.Entry<String, Map<String, Integer>> entry : custom.entrySet()) {
                merged.computeIfAbsent(entry.getKey(), ignored -> new LinkedHashMap<>());
                Map<String, Integer> target = merged.get(entry.getKey());
                if (entry.getValue().containsKey("min")) target.put("min", entry.getValue().get("min"));
                if (entry.getValue().containsKey("max")) target.put("max", entry.getValue().get("max"));
            }
        }
        return merged;
    }

    private Map<String, Integer> quota(int min, int max) {
        return new LinkedHashMap<>(Map.of("min", min, "max", max));
    }

    private List<Map<String, Object>> applyTypeQuotas(List<Map<String, Object>> scored,
                                                      Map<String, Map<String, Integer>> quotas,
                                                      int limit) {
        if (limit <= 0 || scored == null || scored.isEmpty()) {
            return List.of();
        }
        Map<String, List<Map<String, Object>>> byType = new LinkedHashMap<>();
        for (Map<String, Object> item : scored) {
            String type = String.valueOf(item.getOrDefault("type", ""));
            byType.computeIfAbsent(type, ignored -> new ArrayList<>()).add(item);
        }
        for (List<Map<String, Object>> items : byType.values()) {
            items.sort((a, b) -> Double.compare(
                    ((Number) b.getOrDefault("score", 0.0)).doubleValue(),
                    ((Number) a.getOrDefault("score", 0.0)).doubleValue()
            ));
        }

        List<Map<String, Object>> selected = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Set<String> usedIds = new LinkedHashSet<>();

        for (Map.Entry<String, Map<String, Integer>> entry : quotas.entrySet()) {
            String type = entry.getKey();
            int min = Math.max(entry.getValue().getOrDefault("min", 0), 0);
            List<Map<String, Object>> items = byType.getOrDefault(type, List.of());
            for (int i = 0; i < Math.min(min, items.size()) && selected.size() < limit; i++) {
                Map<String, Object> item = items.get(i);
                String id = String.valueOf(item.getOrDefault("id", ""));
                if (usedIds.add(id)) {
                    selected.add(item);
                    counts.put(type, counts.getOrDefault(type, 0) + 1);
                }
            }
        }

        List<Map<String, Object>> remaining = new ArrayList<>(scored);
        remaining.sort((a, b) -> Double.compare(
                ((Number) b.getOrDefault("score", 0.0)).doubleValue(),
                ((Number) a.getOrDefault("score", 0.0)).doubleValue()
        ));
        for (Map<String, Object> item : remaining) {
            if (selected.size() >= limit) break;
            String id = String.valueOf(item.getOrDefault("id", ""));
            if (!usedIds.add(id)) continue;
            String type = String.valueOf(item.getOrDefault("type", ""));
            int max = quotas.getOrDefault(type, Map.of()).getOrDefault("max", Integer.MAX_VALUE);
            if (counts.getOrDefault(type, 0) >= max) continue;
            selected.add(item);
            counts.put(type, counts.getOrDefault(type, 0) + 1);
        }
        return selected;
    }

    private List<Map<String, Object>> extractTopSources(List<Map<String, Object>> items, int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> item : items) {
            if (result.size() >= limit) break;
            Map<String, Object> source = item.get("source") instanceof Map<?, ?> map ? castStringObjectMap(map) : Map.of();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", item.getOrDefault("type", ""));
            entry.put("chapter", source.getOrDefault("chapter", null));
            entry.put("path", source.getOrDefault("path", null));
            entry.put("field", source.getOrDefault("field", null));
            String key = entry.values().toString();
            if (seen.add(key)) {
                result.add(entry);
            }
        }
        return result;
    }

    private Map<String, Object> castStringObjectMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private String stringValue(Map<?, ?> map, String key, String fallback) {
        if (map == null) return fallback;
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }
}
