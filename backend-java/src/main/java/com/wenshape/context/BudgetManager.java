package com.wenshape.context;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * 上下文预算管理器 - 动态 token 预算分配
 */
@Slf4j
public class BudgetManager {
    
    @Getter
    private final String modelName;
    @Getter
    private final int maxOutputTokens;
    @Getter
    private final int contextWindow;
    @Getter
    private final int totalBudget;
    
    // 预算比例
    private final Map<String, Double> ratios;
    
    // 使用追踪
    private final Map<String, BudgetUsage> usage = new HashMap<>();
    
    // 默认上下文窗口大小（按模型）
    private static final Map<String, Integer> MODEL_CONTEXT_WINDOWS = Map.of(
            "gpt-4o", 128000,
            "gpt-4-turbo", 128000,
            "gpt-4", 8192,
            "gpt-3.5-turbo", 16385,
            "claude-3-opus", 200000,
            "claude-3-sonnet", 200000,
            "deepseek-chat", 64000,
            "qwen-max", 32000
    );
    
    public BudgetManager(String modelName, int maxOutputTokens) {
        this.modelName = modelName;
        this.maxOutputTokens = maxOutputTokens;
        
        // 获取模型上下文窗口
        this.contextWindow = getModelContextWindow(modelName);
        
        // 默认预算比例
        this.ratios = new HashMap<>();
        ratios.put("system_rules", 0.05);
        ratios.put("cards", 0.15);
        ratios.put("canon", 0.10);
        ratios.put("summaries", 0.20);
        ratios.put("current_draft", 0.30);
        ratios.put("output_reserve", 0.20);
        
        // 计算总预算
        this.totalBudget = calculateTotalBudget();
    }
    
    private int getModelContextWindow(String model) {
        if (model == null) {
            return 128000;
        }
        
        // 精确匹配
        if (MODEL_CONTEXT_WINDOWS.containsKey(model)) {
            return MODEL_CONTEXT_WINDOWS.get(model);
        }
        
        // 前缀匹配
        String lowerModel = model.toLowerCase();
        for (Map.Entry<String, Integer> entry : MODEL_CONTEXT_WINDOWS.entrySet()) {
            if (lowerModel.contains(entry.getKey().toLowerCase())) {
                return entry.getValue();
            }
        }
        
        // 默认
        return 128000;
    }
    
    private int calculateTotalBudget() {
        int outputReserve = (int) (contextWindow * ratios.get("output_reserve"));
        outputReserve = Math.max(outputReserve, maxOutputTokens);
        return contextWindow - outputReserve;
    }
    
    /**
     * 获取预算分配
     */
    public BudgetAllocation getAllocation() {
        // 计算输入比例（不含 output_reserve）
        Map<String, Double> inputRatios = new HashMap<>(ratios);
        inputRatios.remove("output_reserve");
        
        double ratioSum = inputRatios.values().stream().mapToDouble(Double::doubleValue).sum();
        
        // 归一化
        Map<String, Double> normalized = new HashMap<>();
        for (Map.Entry<String, Double> entry : inputRatios.entrySet()) {
            normalized.put(entry.getKey(), entry.getValue() / ratioSum);
        }
        
        int systemRules = (int) (totalBudget * normalized.getOrDefault("system_rules", 0.0625));
        int cards = (int) (totalBudget * normalized.getOrDefault("cards", 0.1875));
        int canon = (int) (totalBudget * normalized.getOrDefault("canon", 0.125));
        int summaries = (int) (totalBudget * normalized.getOrDefault("summaries", 0.25));
        int currentDraft = (int) (totalBudget * normalized.getOrDefault("current_draft", 0.375));
        int outputReserve = contextWindow - totalBudget;
        
        int used = systemRules + cards + canon + summaries + currentDraft;
        int remaining = totalBudget - used;
        
        return BudgetAllocation.builder()
                .totalAvailable(totalBudget)
                .systemRules(systemRules)
                .cards(cards)
                .canon(canon)
                .summaries(summaries)
                .currentDraft(currentDraft)
                .outputReserve(outputReserve)
                .remaining(remaining)
                .build();
    }
    
    /**
     * 为特定 Agent 分配预算
     */
    public Map<String, Integer> allocateForAgent(String agentName) {
        BudgetAllocation base = getAllocation();
        
        // Agent 特定调整
        Map<String, Map<String, Double>> adjustments = Map.of(
                "archivist", Map.of("cards", 1.2, "canon", 1.3, "summaries", 0.8, "current_draft", 0.7),
                "writer", Map.of("cards", 1.0, "canon", 1.0, "summaries", 1.2, "current_draft", 1.1),
                "editor", Map.of("cards", 0.8, "canon", 0.8, "summaries", 0.9, "current_draft", 1.3)
        );
        
        Map<String, Double> adj = adjustments.getOrDefault(agentName, Map.of());
        
        Map<String, Integer> result = new HashMap<>();
        result.put("system_rules", base.getSystemRules());
        result.put("cards", (int) (base.getCards() * adj.getOrDefault("cards", 1.0)));
        result.put("canon", (int) (base.getCanon() * adj.getOrDefault("canon", 1.0)));
        result.put("summaries", (int) (base.getSummaries() * adj.getOrDefault("summaries", 1.0)));
        result.put("current_draft", (int) (base.getCurrentDraft() * adj.getOrDefault("current_draft", 1.0)));
        result.put("total_available", base.getTotalAvailable());
        result.put("output_reserve", base.getOutputReserve());
        
        return result;
    }
    
    /**
     * 追踪预算使用
     */
    public BudgetUsage trackUsage(String category, String content, int itemsCount) {
        int tokens = estimateTokens(content);
        BudgetAllocation allocation = getAllocation();
        
        int allocated = switch (category) {
            case "system_rules" -> allocation.getSystemRules();
            case "cards" -> allocation.getCards();
            case "canon" -> allocation.getCanon();
            case "summaries" -> allocation.getSummaries();
            case "current_draft" -> allocation.getCurrentDraft();
            default -> 0;
        };
        
        BudgetUsage budgetUsage = usage.computeIfAbsent(category, 
                k -> new BudgetUsage(category, allocated, 0, 0));
        
        budgetUsage.setUsed(budgetUsage.getUsed() + tokens);
        budgetUsage.setItemsCount(budgetUsage.getItemsCount() + itemsCount);
        
        return budgetUsage;
    }
    
    /**
     * 检查内容是否能放入指定类别
     */
    public boolean canFit(String content, String category) {
        int tokens = estimateTokens(content);
        int remaining = getRemaining(category);
        return tokens <= remaining;
    }
    
    /**
     * 获取指定类别的剩余预算
     */
    public int getRemaining(String category) {
        BudgetAllocation allocation = getAllocation();
        
        int allocated = switch (category) {
            case "system_rules" -> allocation.getSystemRules();
            case "cards" -> allocation.getCards();
            case "canon" -> allocation.getCanon();
            case "summaries" -> allocation.getSummaries();
            case "current_draft" -> allocation.getCurrentDraft();
            default -> 0;
        };
        
        BudgetUsage budgetUsage = usage.get(category);
        int used = budgetUsage != null ? budgetUsage.getUsed() : 0;
        
        return Math.max(0, allocated - used);
    }
    
    /**
     * 获取使用情况摘要
     */
    public Map<String, Object> getUsageSummary() {
        int totalUsed = usage.values().stream().mapToInt(BudgetUsage::getUsed).sum();
        
        Map<String, Object> categories = new HashMap<>();
        for (Map.Entry<String, BudgetUsage> entry : usage.entrySet()) {
            BudgetUsage u = entry.getValue();
            categories.put(entry.getKey(), Map.of(
                    "allocated", u.getAllocated(),
                    "used", u.getUsed(),
                    "remaining", u.getRemaining(),
                    "items", u.getItemsCount(),
                    "ratio", u.getUsageRatio()
            ));
        }
        
        return Map.of(
                "model", modelName != null ? modelName : "",
                "context_window", contextWindow,
                "total_budget", totalBudget,
                "total_used", totalUsed,
                "usage_ratio", totalBudget > 0 ? (double) totalUsed / totalBudget : 0,
                "categories", categories
        );
    }
    
    /**
     * 估算 token 数量
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        // 简化估算：混合文本约 2.5 字符/token
        return (int) Math.ceil(text.length() / 2.5);
    }
    
    /**
     * 预算使用情况
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class BudgetUsage {
        private String category;
        private int allocated;
        private int used;
        private int itemsCount;
        
        public int getRemaining() {
            return Math.max(0, allocated - used);
        }
        
        public double getUsageRatio() {
            return allocated > 0 ? (double) used / allocated : 0;
        }
    }
}
