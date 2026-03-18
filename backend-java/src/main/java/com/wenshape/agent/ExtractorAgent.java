package com.wenshape.agent;

import com.wenshape.llm.LlmGateway;
import com.wenshape.llm.LlmResponse;
import com.wenshape.model.entity.ChapterSummary;
import com.wenshape.model.entity.Fact;
import com.wenshape.storage.CardStorage;
import com.wenshape.storage.DraftStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提取器 Agent - 从章节内容提取摘要和事实
 */
@Slf4j
@Component
public class ExtractorAgent extends BaseAgent {
    
    public ExtractorAgent(LlmGateway llmGateway, CardStorage cardStorage, DraftStorage draftStorage) {
        super(llmGateway, cardStorage, draftStorage, "zh");
    }
    
    @Override
    public String getAgentName() {
        return "extractor";
    }
    
    @Override
    public String getSystemPrompt() {
        return """
                ### 角色定位
                你是 WenShape 系统的 Archivist（资料管理员），一位精通信息结构化的知识工程师。
                核心职责：将文本内容转换为可落库的结构化信息。
                
                ### 专业能力
                - 擅长：信息抽取、结构化转换、一致性维护、知识图谱构建
                - 输出类型：事实/时间线/角色状态/摘要
                
                ==================================================
                ### 核心约束（信息保真原则）
                ==================================================
                
                【P0-必须】证据约束：
                  - 仅依据输入内容进行抽取
                  - 禁止捏造任何输入未明确包含的信息
                  - 不确定时：留空 / 空列表
                
                【P0-必须】输出格式：
                  - 按指定格式输出，禁止添加多余说明
                  - 禁止输出思维过程
                
                【P1-应当】优先抽取（对后文有约束力的信息）：
                  - 规则/禁忌/代价（世界观硬约束）
                  - 关键关系变化、重要状态转变、关键事件节点
                
                【P1-应当】避免抽取：
                  - 琐碎重复信息
                  - 推测性内容（推测不能当事实）
                
                【P1-应当】命名一致性：
                  - 使用输入中出现的原名，禁止擅自改名
                
                ### 自检清单（内部执行）
                □ 是否包含多余的说明文字？
                □ 是否存在「输入没有但觉得合理」的捏造？
                """;
    }
    
    /**
     * 生成章节摘要
     */
    public ChapterSummary generateChapterSummary(String projectId, String chapter, 
                                                   String chapterTitle, String content) {
        List<Map<String, String>> messages = buildSummaryMessages(chapter, chapterTitle, content);
        
        LlmResponse response = callLlm(messages);
        return parseSummaryResponse(response.getContent(), chapter, chapterTitle, content.length());
    }
    
    /**
     * 提取事实更新
     */
    public Map<String, Object> extractCanonUpdates(String projectId, String chapter, String content) {
        List<Map<String, String>> messages = buildCanonMessages(chapter, content);
        
        LlmResponse response = callLlm(messages);
        return parseCanonResponse(response.getContent(), chapter);
    }
    
    private List<Map<String, String>> buildSummaryMessages(String chapter, String chapterTitle, String content) {
        String userPrompt = String.format("""
                请分析以下章节内容，生成结构化摘要：
                
                章节ID: %s
                章节标题: %s
                
                章节内容：
                %s
                
                请输出：
                1. 简要摘要（100-200字）
                2. 关键事件列表（3-5项）
                3. 新出现的设定事实（如有）
                4. 角色状态变化（如有）
                5. 未解决的伏笔（如有）
                
                格式示例：
                【摘要】
                本章讲述了...
                
                【关键事件】
                - 事件1
                - 事件2
                
                【新事实】
                - 事实1
                
                【角色状态】
                - 角色A：状态变化
                
                【伏笔】
                - 伏笔1
                """, chapter, chapterTitle, truncateContent(content, 8000));
        
        return buildMessages(getSystemPrompt(), userPrompt, List.of());
    }
    
    private List<Map<String, String>> buildCanonMessages(String chapter, String content) {
        String userPrompt = String.format("""
                请从以下章节内容中提取设定事实：
                
                章节ID: %s
                
                章节内容：
                %s
                
                请提取：
                1. 世界观设定（地点、组织、规则等）
                2. 角色背景信息
                3. 重要物品或能力
                4. 时间线事件
                
                每条事实用一句话描述，确保准确客观。
                最多提取5条最重要的事实。
                
                格式：
                【事实】
                - 事实1
                - 事实2
                
                【时间线】
                - 事件1（时间点）
                """, chapter, truncateContent(content, 8000));
        
        return buildMessages(getSystemPrompt(), userPrompt, List.of());
    }
    
    private ChapterSummary parseSummaryResponse(String response, String chapter, 
                                                  String chapterTitle, int wordCount) {
        ChapterSummary summary = ChapterSummary.builder()
                .chapter(chapter)
                .title(chapterTitle)
                .wordCount(wordCount)
                .build();
        
        // 提取摘要
        String briefSummary = extractSection(response, "【摘要】", "【");
        if (briefSummary != null && !briefSummary.isBlank()) {
            summary.setBriefSummary(briefSummary.trim());
        }
        
        // 提取关键事件
        List<String> keyEvents = extractListSection(response, "【关键事件】", "【");
        summary.setKeyEvents(keyEvents);
        
        // 提取新事实
        List<String> newFacts = extractListSection(response, "【新事实】", "【");
        summary.setNewFacts(newFacts);
        
        // 提取角色状态
        List<String> characterStates = extractListSection(response, "【角色状态】", "【");
        summary.setCharacterStateChanges(characterStates);
        
        // 提取伏笔
        List<String> openLoops = extractListSection(response, "【伏笔】", "【");
        summary.setOpenLoops(openLoops);
        
        // 提取卷ID
        summary.setVolumeId(extractVolumeId(chapter));
        
        return summary;
    }
    
    private Map<String, Object> parseCanonResponse(String response, String chapter) {
        Map<String, Object> result = new HashMap<>();
        
        // 提取事实
        List<String> factStrings = extractListSection(response, "【事实】", "【");
        List<Fact> facts = new ArrayList<>();
        int idx = 1;
        for (String statement : factStrings) {
            if (statement != null && !statement.isBlank()) {
                Fact fact = Fact.builder()
                        .id(String.format("F%04d", idx++))
                        .statement(statement.trim())
                        .introducedIn(chapter)
                        .build();
                facts.add(fact);
            }
        }
        result.put("facts", facts);
        
        // 提取时间线事件
        List<String> timelineStrings = extractListSection(response, "【时间线】", "【");
        List<Map<String, String>> timelineEvents = new ArrayList<>();
        for (String event : timelineStrings) {
            if (event != null && !event.isBlank()) {
                timelineEvents.add(Map.of(
                        "description", event.trim(),
                        "source", chapter
                ));
            }
        }
        result.put("timeline_events", timelineEvents);
        
        return result;
    }
    
    private String extractSection(String text, String startMarker, String endMarker) {
        int start = text.indexOf(startMarker);
        if (start < 0) return null;
        
        start += startMarker.length();
        int end = text.indexOf(endMarker, start);
        if (end < 0) end = text.length();
        
        return text.substring(start, end).trim();
    }
    
    private List<String> extractListSection(String text, String startMarker, String endMarker) {
        String section = extractSection(text, startMarker, endMarker);
        if (section == null || section.isBlank()) {
            return new ArrayList<>();
        }
        
        List<String> items = new ArrayList<>();
        for (String line : section.split("\n")) {
            line = line.trim();
            if (line.startsWith("-") || line.startsWith("•") || line.startsWith("*")) {
                line = line.substring(1).trim();
            }
            if (!line.isBlank()) {
                items.add(line);
            }
        }
        
        return items;
    }
    
    private String extractVolumeId(String chapter) {
        Pattern pattern = Pattern.compile("V(\\d+)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(chapter);
        if (matcher.find()) {
            return "V" + matcher.group(1);
        }
        return "V1";
    }
    
    private String truncateContent(String content, int maxLength) {
        if (content == null) return "";
        if (content.length() <= maxLength) return content;
        return content.substring(0, maxLength) + "\n...[内容已截断]";
    }
}
