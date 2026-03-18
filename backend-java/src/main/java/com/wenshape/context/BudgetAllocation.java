package com.wenshape.context;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 预算分配结果
 */
@Data
@Builder
public class BudgetAllocation {
    private int totalAvailable;    // 总可用 tokens
    private int systemRules;       // 系统规则预算
    private int cards;             // 卡片预算
    private int canon;             // 事实表预算
    private int summaries;         // 摘要预算
    private int currentDraft;      // 当前草稿预算
    private int outputReserve;     // 输出预留
    @Builder.Default
    private int remaining = 0;     // 剩余可用
    
    public Map<String, Integer> toMap() {
        Map<String, Integer> map = new HashMap<>();
        map.put("total_available", totalAvailable);
        map.put("system_rules", systemRules);
        map.put("cards", cards);
        map.put("canon", canon);
        map.put("summaries", summaries);
        map.put("current_draft", currentDraft);
        map.put("output_reserve", outputReserve);
        map.put("remaining", remaining);
        return map;
    }
}
