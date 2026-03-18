package com.wenshape.context;

import com.wenshape.model.entity.CharacterCard;
import com.wenshape.model.entity.Fact;
import com.wenshape.model.entity.StyleCard;
import com.wenshape.model.entity.WorldCard;
import com.wenshape.storage.CardStorage;
import com.wenshape.storage.CanonStorage;
import com.wenshape.storage.DraftStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 上下文选择引擎 - 智能选择相关上下文项
 */
@Slf4j
@Component
public class ContextSelectEngine {
    
    private static final int MAX_CANDIDATES_PER_TYPE = 50;
    
    private final CardStorage cardStorage;
    private final CanonStorage canonStorage;
    private final DraftStorage draftStorage;

    public ContextSelectEngine(CardStorage cardStorage, CanonStorage canonStorage, DraftStorage draftStorage) {
        this.cardStorage = cardStorage;
        this.canonStorage = canonStorage;
        this.draftStorage = draftStorage;
    }
    
    /**
     * 确定性选择 - 加载特定智能体必须使用的项
     */
    public List<ContextItem> deterministicSelect(String projectId, String agentName) {
        List<ContextItem> items = new ArrayList<>();
        
        Map<String, List<String>> alwaysLoadMap = Map.of(
                "archivist", List.of("style_card"),
                "writer", List.of("style_card", "scene_brief"),
                "editor", List.of("style_card")
        );
        
        List<String> itemTypes = alwaysLoadMap.getOrDefault(agentName, List.of());
        
        for (String itemType : itemTypes) {
            ContextItem item = loadItem(projectId, itemType);
            if (item != null) {
                item.setPriority(ContextPriority.CRITICAL);
                items.add(item);
            }
        }
        
        return items;
    }
    
    private ContextItem loadItem(String projectId, String itemType) {
        try {
            if ("style_card".equals(itemType)) {
                Optional<StyleCard> cardOpt = cardStorage.getStyleCard(projectId);
                if (cardOpt.isPresent()) {
                    String content = formatCard(cardOpt.get());
                    if (looksLikeRewritePrompt(content)) {
                        log.warn("Style card looks like a rewrite prompt; skipping injection for safety.");
                        return null;
                    }
                    return ContextItem.builder()
                            .id("style_card")
                            .type(ContextType.STYLE_CARD)
                            .content(content)
                            .priority(ContextPriority.CRITICAL)
                            .build();
                }
            }
        } catch (Exception e) {
            log.warn("Error loading {}: {}", itemType, e.getMessage());
        }
        return null;
    }
    
    private String formatCard(Object card) {
        if (card instanceof StyleCard styleCard) {
            StringBuilder sb = new StringBuilder();
            if (styleCard.getStyle() != null) {
                sb.append("style: ").append(styleCard.getStyle()).append("\n");
            }
            return sb.toString();
        }
        return card.toString();
    }
    
    private boolean looksLikeRewritePrompt(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        
        String compact = text.replaceAll("\\s+", "").toLowerCase();
        
        // 强标记
        List<String> strongMarkers = List.of(
                "chaoticspokenflow", "一逗到底", "极致拟人化", 
                "混沌增益", "strictprohibitions", "executionsteps"
        );
        
        for (String marker : strongMarkers) {
            if (compact.contains(marker.toLowerCase())) {
                return true;
            }
        }
        
        // AI 检测规避
        if (compact.contains("ai检测") || compact.contains("ai检测工具")) {
            return true;
        }
        
        return false;
    }
    
    /**
     * 检索式选择 - 基于查询相关性排序
     */
    public List<ContextItem> retrievalSelect(String projectId, String query, 
                                              List<String> itemTypes, int topK) {
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        
        List<ContextItem> candidates = new ArrayList<>();
        
        // 角色卡
        if (itemTypes.contains("character")) {
            try {
                List<String> names = cardStorage.listCharacterCards(projectId);
                for (String name : names.subList(0, Math.min(names.size(), MAX_CANDIDATES_PER_TYPE))) {
                    Optional<CharacterCard> cardOpt = cardStorage.getCharacterCard(projectId, name);
                    if (cardOpt.isPresent()) {
                        String content = formatCharacterCard(cardOpt.get());
                        double score = scoreText(query, content);
                        if (score > 0) {
                            candidates.add(ContextItem.builder()
                                    .id("char_" + name)
                                    .type(ContextType.CHARACTER_CARD)
                                    .content(content)
                                    .priority(ContextPriority.MEDIUM)
                                    .relevanceScore(score)
                                    .metadata(Map.of("name", name))
                                    .build());
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to list character cards: {}", e.getMessage());
            }
        }
        
        // 世界观卡
        if (itemTypes.contains("world")) {
            try {
                List<String> names = cardStorage.listWorldCards(projectId);
                for (String name : names.subList(0, Math.min(names.size(), MAX_CANDIDATES_PER_TYPE))) {
                    Optional<WorldCard> cardOpt = cardStorage.getWorldCard(projectId, name);
                    if (cardOpt.isPresent()) {
                        String content = formatWorldCard(cardOpt.get());
                        double score = scoreText(query, content);
                        if (score > 0) {
                            candidates.add(ContextItem.builder()
                                    .id("world_" + name)
                                    .type(ContextType.WORLD_CARD)
                                    .content(content)
                                    .priority(ContextPriority.MEDIUM)
                                    .relevanceScore(score)
                                    .metadata(Map.of("name", name))
                                    .build());
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to list world cards: {}", e.getMessage());
            }
        }
        
        // 事实
        if (itemTypes.contains("fact")) {
            try {
                List<Fact> facts = canonStorage.listFacts(projectId);
                int idx = 0;
                for (Fact fact : facts.subList(0, Math.min(facts.size(), MAX_CANDIDATES_PER_TYPE))) {
                    String statement = fact.getStatement();
                    if (statement != null && !statement.isBlank()) {
                        double score = scoreText(query, statement);
                        if (score > 0) {
                            String factId = fact.getId() != null ? fact.getId() : String.format("F%04d", idx + 1);
                            candidates.add(ContextItem.builder()
                                    .id(factId)
                                    .type(ContextType.FACT)
                                    .content(statement)
                                    .priority(ContextPriority.MEDIUM)
                                    .relevanceScore(score)
                                    .metadata(Map.of("introduced_in", 
                                            fact.getIntroducedIn() != null ? fact.getIntroducedIn() : ""))
                                    .build());
                        }
                    }
                    idx++;
                }
            } catch (Exception e) {
                log.warn("Failed to load facts: {}", e.getMessage());
            }
        }

        // 文本分块
        if (itemTypes.contains("text_chunk")) {
            try {
                List<Map<String, Object>> chunks = draftStorage.searchTextChunks(
                        projectId, query, Math.min(topK * 2, MAX_CANDIDATES_PER_TYPE), null, null, false
                );
                for (Map<String, Object> chunk : chunks) {
                    String text = String.valueOf(chunk.getOrDefault("text", ""));
                    if (text.isBlank()) {
                        continue;
                    }
                    String chapter = String.valueOf(chunk.getOrDefault("chapter", ""));
                    double score = chunk.get("score") instanceof Number n ? n.doubleValue() : scoreText(query, text);
                    candidates.add(ContextItem.builder()
                            .id("chunk_" + chapter + "_" + chunk.getOrDefault("chunk_index", 0))
                            .type(ContextType.TEXT_CHUNK)
                            .content(text)
                            .priority(ContextPriority.MEDIUM)
                            .relevanceScore(score)
                            .metadata(Map.of(
                                    "chapter", chapter,
                                    "chunk_index", chunk.getOrDefault("chunk_index", 0)
                            ))
                            .build());
                }
            } catch (Exception e) {
                log.warn("Failed to load text chunks: {}", e.getMessage());
            }
        }
        
        // 按相关性排序并返回 top-k
        return candidates.stream()
                .sorted(Comparator.comparingDouble(ContextItem::getRelevanceScore).reversed())
                .limit(topK)
                .collect(Collectors.toList());
    }
    
    /**
     * 计算文本相关性分数（简化的 BM25 + 重叠分数）
     */
    private double scoreText(String query, String text) {
        if (query == null || text == null || query.isBlank() || text.isBlank()) {
            return 0.0;
        }
        
        // 分词（简化：按空格和标点分割）
        Set<String> queryTerms = tokenize(query);
        Set<String> textTerms = tokenize(text);
        
        if (queryTerms.isEmpty() || textTerms.isEmpty()) {
            return 0.0;
        }
        
        // 计算重叠
        Set<String> intersection = new HashSet<>(queryTerms);
        intersection.retainAll(textTerms);
        
        double overlap = (double) intersection.size() / queryTerms.size();
        
        // 简化的 BM25 分数
        double bm25 = 0.0;
        double k1 = 1.2;
        double b = 0.75;
        double avgDl = 100.0; // 假设平均文档长度
        double dl = textTerms.size();
        
        for (String term : queryTerms) {
            if (textTerms.contains(term)) {
                double tf = 1.0; // 简化：假设词频为1
                double idf = Math.log(1 + 1.0); // 简化的 IDF
                bm25 += idf * (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * dl / avgDl));
            }
        }
        
        // 混合分数
        return overlap * 0.35 + bm25 * 0.65;
    }
    
    private Set<String> tokenize(String text) {
        if (text == null) {
            return Set.of();
        }
        
        // 简化分词：按非字母数字字符分割，转小写
        return Arrays.stream(text.toLowerCase().split("[^\\p{L}\\p{N}]+"))
                .filter(s -> !s.isBlank() && s.length() > 1)
                .collect(Collectors.toSet());
    }
    
    private String formatCharacterCard(CharacterCard card) {
        StringBuilder sb = new StringBuilder();
        sb.append("name: ").append(card.getName()).append("\n");
        if (card.getDescription() != null) {
            sb.append("description: ").append(card.getDescription()).append("\n");
        }
        if (card.getAliases() != null && !card.getAliases().isEmpty()) {
            sb.append("aliases: ").append(String.join(", ", card.getAliases())).append("\n");
        }
        return sb.toString();
    }
    
    private String formatWorldCard(WorldCard card) {
        StringBuilder sb = new StringBuilder();
        sb.append("name: ").append(card.getName()).append("\n");
        if (card.getCategory() != null) {
            sb.append("category: ").append(card.getCategory()).append("\n");
        }
        if (card.getDescription() != null) {
            sb.append("description: ").append(card.getDescription()).append("\n");
        }
        if (card.getRules() != null && !card.getRules().isEmpty()) {
            sb.append("rules:\n");
            for (String rule : card.getRules()) {
                sb.append("  - ").append(rule).append("\n");
            }
        }
        return sb.toString();
    }
}
