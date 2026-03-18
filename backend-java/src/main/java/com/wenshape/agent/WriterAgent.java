package com.wenshape.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenshape.context.ContextItem;
import com.wenshape.context.ContextSelectEngine;
import com.wenshape.llm.LlmGateway;
import com.wenshape.llm.LlmResponse;
import com.wenshape.model.entity.*;
import com.wenshape.storage.CardStorage;
import com.wenshape.storage.DraftStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 撰稿人 Agent - 生成章节草稿
 */
@Slf4j
@Component
public class WriterAgent extends BaseAgent {

    private static final int DEFAULT_TARGET_WORD_COUNT = 3000;
    private static final Pattern THINK_PATTERN = Pattern.compile("<think>.*?</think>", Pattern.DOTALL);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ContextSelectEngine selectEngine;

    // gap 支撑分数阈值（对齐 Python 版 MIN_GAP_SUPPORT_SCORE = 3.0）
    private static final double MIN_GAP_SUPPORT_SCORE = 0.15; // Java scoreText 分数范围不同，用 0.15

    // 默认预写问题（LLM 生成失败时的回退）
    private static final List<Map<String, String>> DEFAULT_QUESTIONS_ZH = List.of(
        Map.of("type", "plot_point",      "text", "为达成本章目标，尚缺的剧情/世界信息是什么？"),
        Map.of("type", "character_change","text", "哪些主角的动机或情绪需再确认，避免违背既有事实？"),
        Map.of("type", "detail_gap",      "text", "还有哪些具体细节（地点/时间/物件）需要确定后再写？")
    );

    public WriterAgent(LlmGateway llmGateway, CardStorage cardStorage, DraftStorage draftStorage,
                       ContextSelectEngine selectEngine) {
        super(llmGateway, cardStorage, draftStorage, "zh");
        this.selectEngine = selectEngine;
    }
    
    @Override
    public String getAgentName() {
        return "writer";
    }
    
    @Override
    public String getSystemPrompt() {
        return getWriterSystemPrompt();
    }
    
    /**
     * 执行写作 - 生成章节草稿
     */
    public Map<String, Object> execute(String projectId, String chapter, Map<String, Object> context) {
        SceneBrief sceneBrief = (SceneBrief) context.get("sceneBrief");
        if (sceneBrief == null) {
            sceneBrief = draftStorage.getSceneBrief(projectId, chapter).orElse(null);
        }
        
        if (sceneBrief == null) {
            return Map.of("success", false, "error", "Scene brief not found");
        }
        
        int targetWordCount = context.containsKey("targetWordCount") 
                ? (int) context.get("targetWordCount") 
                : DEFAULT_TARGET_WORD_COUNT;
        
        // 构建消息
        List<Map<String, String>> messages = buildDraftMessages(sceneBrief, targetWordCount, context);
        
        // 调用 LLM
        LlmResponse response = callLlm(messages);
        String draftContent = extractDraftContent(response.getContent());
        
        // 保存草稿
        try {
            Draft draft = draftStorage.saveCurrentDraft(
                    projectId, chapter, draftContent, draftContent.length(), null, true
            );
            
            return Map.of(
                    "success", true,
                    "draft", draft,
                    "wordCount", draftContent.length()
            );
        } catch (Exception e) {
            log.error("保存草稿失败", e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }
    
    /**
     * 流式生成草稿
     */
    public Flux<String> executeStreamDraft(String projectId, String chapter, Map<String, Object> context) {
        SceneBrief sceneBrief = (SceneBrief) context.get("sceneBrief");
        if (sceneBrief == null) {
            sceneBrief = draftStorage.getSceneBrief(projectId, chapter).orElse(null);
        }
        
        if (sceneBrief == null) {
            return Flux.just("[Error: Scene brief not found]");
        }
        
        int targetWordCount = context.containsKey("targetWordCount")
                ? (int) context.get("targetWordCount")
                : DEFAULT_TARGET_WORD_COUNT;
        
        List<Map<String, String>> messages = buildDraftMessages(sceneBrief, targetWordCount, context);
        
        // 流式调用 LLM，过滤 <think> 标签
        return callLlmStream(messages)
                .scan(new ThinkFilterState(), this::filterThinkTag)
                .filter(state -> !state.output.isEmpty())
                .map(state -> state.output);
    }
    
    /**
     * 构建草稿生成消息（对齐 Python _build_draft_messages，include_plan=true）
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, String>> buildDraftMessages(SceneBrief sceneBrief, int targetWordCount,
                                                          Map<String, Object> context) {
        List<String> contextItems = new ArrayList<>();

        // -- 1. 场景简报 ------------------------------------------------------
        StringBuilder briefText = new StringBuilder("Scene Brief:\n");
        briefText.append("Chapter: ").append(sceneBrief.getChapter()).append("\n");
        briefText.append("Title: ").append(sceneBrief.getTitle()).append("\n");
        briefText.append("Goal: ").append(sceneBrief.getGoal()).append("\n");

        if (sceneBrief.getCharacters() != null && !sceneBrief.getCharacters().isEmpty()) {
            briefText.append("Characters:\n");
            for (Map<String, String> ch : sceneBrief.getCharacters()) {
                briefText.append("- ").append(ch.get("name"));
                String state = ch.get("current_state");
                String traits = ch.get("relevant_traits");
                if (state != null && !state.isBlank()) briefText.append(" [").append(state).append("]");
                if (traits != null && !traits.isBlank()) briefText.append(": ").append(traits);
                briefText.append("\n");
            }
        }

        if (sceneBrief.getTimelineContext() != null && !sceneBrief.getTimelineContext().isEmpty()) {
            Map<String, String> tc = sceneBrief.getTimelineContext();
            briefText.append("Timeline Context:\n");
            if (!isBlankOrNull(tc.get("before")))   briefText.append("  Before: ").append(tc.get("before")).append("\n");
            if (!isBlankOrNull(tc.get("current")))  briefText.append("  Current: ").append(tc.get("current")).append("\n");
        }

        if (sceneBrief.getWorldConstraints() != null && !sceneBrief.getWorldConstraints().isEmpty()) {
            briefText.append("World Constraints:\n");
            for (String c : sceneBrief.getWorldConstraints()) briefText.append("- ").append(c).append("\n");
        }

        if (!isBlankOrNull(sceneBrief.getStyleReminder()))
            briefText.append("Style Reminder: ").append(sceneBrief.getStyleReminder()).append("\n");

        if (sceneBrief.getForbidden() != null && !sceneBrief.getForbidden().isEmpty()) {
            briefText.append("FORBIDDEN:\n");
            for (String f : sceneBrief.getForbidden()) briefText.append("- ").append(f).append("\n");
        }
        contextItems.add(briefText.toString());

        // -- 2. Working Memory（如有，优先级最高）----------------------------
        String workingMemory = (String) context.get("working_memory");
        if (!isBlankOrNull(workingMemory)) {
            contextItems.add("Working Memory:\n" + workingMemory);
        }

        // -- 3. 文风卡 --------------------------------------------------------
        StyleCard styleCard = (StyleCard) context.get("styleCard");
        if (styleCard != null && !isBlankOrNull(styleCard.getStyle())) {
            contextItems.add("Style Card:\n" + styleCard.getStyle());
        }

        boolean hasWorkingMemory = !isBlankOrNull(workingMemory);

        if (!hasWorkingMemory) {
            // -- 4. 未解决缺口 ------------------------------------------------
            List<Map<String, Object>> unresolvedGaps = (List<Map<String, Object>>) context.get("unresolved_gaps");
            if (unresolvedGaps != null && !unresolvedGaps.isEmpty()) {
                StringBuilder gapText = new StringBuilder("未解决缺口（不得编造，必须留白或用[TO_CONFIRM:…]标记）:\n");
                int cnt = 0;
                for (Map<String, Object> gap : unresolvedGaps) {
                    String t = (String) gap.getOrDefault("text", "");
                    if (!isBlankOrNull(t)) { gapText.append("- ").append(t).append("\n"); if (++cnt >= 6) break; }
                }
                if (cnt > 0) contextItems.add(gapText.toString());
            }

            // -- 5. Text Chunks -----------------------------------------------
            List<Object> textChunks = (List<Object>) context.get("text_chunks");
            if (textChunks != null && !textChunks.isEmpty()) {
                StringBuilder sb = new StringBuilder("Text Chunks:\n");
                int cnt = 0;
                for (Object chunk : textChunks) {
                    if (chunk instanceof Map<?, ?> m) {
                        String ch = m.containsKey("chapter") ? (String) m.get("chapter") : "";
                        String txt = m.containsKey("text") ? (String) m.get("text") : chunk.toString();
                        sb.append(!isBlankOrNull(ch) ? "[" + ch + "] " : "").append(txt).append("\n");
                    } else {
                        sb.append(chunk).append("\n");
                    }
                    if (++cnt >= 6) break;
                }
                contextItems.add(sb.toString());
            }

            // -- 6. Evidence Pack ---------------------------------------------
            Map<String, Object> evidencePack = (Map<String, Object>) context.get("evidence_pack");
            if (evidencePack != null) {
                List<Object> items = (List<Object>) evidencePack.get("items");
                if (items != null && !items.isEmpty()) {
                    StringBuilder sb = new StringBuilder("Evidence Pack:\n");
                    int cnt = 0;
                    for (Object item : items) {
                        if (item instanceof Map<?, ?> m) {
                            String type = m.containsKey("type") ? (String) m.get("type") : "";
                            String text = m.containsKey("text") ? (String) m.get("text") :
                                    m.containsKey("statement") ? (String) m.get("statement") : "";
                            if (!isBlankOrNull(text)) {
                                sb.append(!isBlankOrNull(type) ? "[" + type + "] " : "").append(text).append("\n");
                                if (++cnt >= 12) break;
                            }
                        }
                    }
                    if (cnt > 0) contextItems.add(sb.toString());
                }
            }

            // -- 7. Character Cards -------------------------------------------
            List<String> characterCards = (List<String>) context.get("character_cards");
            if (characterCards != null && !characterCards.isEmpty()) {
                StringBuilder sb = new StringBuilder("Character Cards:\n");
                characterCards.stream().limit(10).forEach(c -> sb.append(c).append("\n"));
                contextItems.add(sb.toString());
            }

            // -- 8. World Cards -----------------------------------------------
            List<String> worldCards = (List<String>) context.get("world_cards");
            if (worldCards != null && !worldCards.isEmpty()) {
                StringBuilder sb = new StringBuilder("World Cards:\n");
                worldCards.stream().limit(10).forEach(c -> sb.append(c).append("\n"));
                contextItems.add(sb.toString());
            }

            // -- 9. Canon Facts（evidence_pack 为空时才用）--------------------
            boolean hasEvidencePack = evidencePack != null &&
                    evidencePack.get("items") instanceof List<?> l && !l.isEmpty();
            if (!hasEvidencePack) {
                List<String> facts = (List<String>) context.get("facts");
                if (facts != null && !facts.isEmpty()) {
                    StringBuilder sb = new StringBuilder("Canon Facts:\n");
                    facts.stream().limit(20).forEach(f -> sb.append("- ").append(f).append("\n"));
                    contextItems.add(sb.toString());
                }
            }

            // -- 10. Character States -----------------------------------------
            List<String> characterStates = (List<String>) context.get("character_states");
            if (characterStates != null && !characterStates.isEmpty()) {
                StringBuilder sb = new StringBuilder("Character States:\n");
                characterStates.stream().limit(20).forEach(s -> sb.append(s).append("\n"));
                contextItems.add(sb.toString());
            }
        }

        // -- 11. 用户预写问答 -------------------------------------------------
        Object rawAnswers = context.get("user_answers");
        if (rawAnswers instanceof List<?> answerList && !answerList.isEmpty()) {
            StringBuilder answersText = new StringBuilder("User Answers:\n");
            for (Object item : answerList) {
                if (item instanceof Map<?, ?> m) {
                    Object qObj = m.containsKey("question") ? m.get("question") : m.get("text");
                    Object aObj = m.get("answer");
                    String q = qObj != null ? qObj.toString() : "";
                    String a = aObj != null ? aObj.toString() : "";
                    if (!q.isBlank() && !a.isBlank()) {
                        answersText.append("- ").append(q).append(": ").append(a).append("\n");
                    }
                }
            }
            if (answersText.length() > 15) contextItems.add(answersText.toString());
        }

        // -- 12. 用户反馈 -----------------------------------------------------
        String userFeedback = (String) context.get("userFeedback");
        if (!isBlankOrNull(userFeedback)) {
            contextItems.add("User Feedback:\n" + userFeedback);
        }

        // -- 13. 前置章节摘要 -------------------------------------------------
        List<String> previousSummaries = (List<String>) context.get("previousSummaries");
        if (previousSummaries != null && !previousSummaries.isEmpty()) {
            contextItems.add("Previous Chapters:\n" + String.join("\n\n", previousSummaries));
        }

        // -- 14. 构建用户提示词（include_plan=true，对齐 Python）---------------
        String chapterGoal = (String) context.get("chapterGoal");
        String userPrompt = buildDraftUserPrompt(chapterGoal, sceneBrief.getGoal(), targetWordCount);

        return buildMessages(getSystemPrompt(), userPrompt, contextItems);
    }
    
    /**
     * 构建草稿用户提示词（对齐 Python writer_draft_prompt include_plan=True）
     */
    private String buildDraftUserPrompt(String chapterGoal, String briefGoal, int targetWordCount) {
        String goal = chapterGoal != null && !chapterGoal.isBlank() ? chapterGoal : briefGoal;
        String effectiveGoal = goal != null ? goal : "推进剧情";

        String critical = String.format("""
                ==================================================
                ### 核心约束（必须遵守）
                ==================================================
                
                【P0-必须】约束1 - 目标优先
                  严格服务于用户指令和章节目标，禁止偏离
                
                【P0-必须】约束2 - 证据约束
                  只使用证据包内容：facts/summaries/cards/text_chunks/working_memory
                  缺乏证据的细节必须标记 [TO_CONFIRM:具体内容]
                
                【P0-必须】约束3 - 冲突处理
                  working_memory > scene_brief > cards（按优先级）
                
                【P0-必须】约束4 - 身份区分
                  不同名字默认为不同人物
                
                【P0-必须】约束5 - 输出纯净
                  正文中禁止出现系统词汇：证据、检索、数据库、工作记忆、卡片、facts、chunks
                
                【P0-必须】约束6 - 反总结腔（去 AI 味）
                  - 禁止作者旁白自顾自总结、下定义、复盘（尤其是段尾）
                  - 禁止叙述句式：他知道/他明白/这意味着/总之/或者说/换句话说/归根结底/可以说
                  - 需要表达判断时，用动作/对白/线索呈现，让读者自己推导
                """);

        return String.format("""
                %s
                
                ### 本次写作任务
                
                章节目标：%s
                目标字数：约 %d 字
                
                ### 写作策略指导
                
                【P1-应当】情绪锚定：将情绪落在具体的剧情锚点
                  - 通过动作、对话、环境变化承载情绪，避免空泛抽象词堆砌
                
                【P1-应当】证据优先：
                  - text_chunks 提供的具体场景/动作/对白，优先据此展开
                  - 禁止反向编造来「吻合」已有内容
                
                【P1-应当】高质感对话（防水防白）：
                  - 人物对话必须符合身份与立场，切忌长篇大论的机械式设定解释
                  - 对话内容应展现人物拉扯、试探或潜台词，不说废话
                
                【P1-应当】超凡力量与动作实感：
                  - 描写力量或动作时，必须具备"物理实感"和"代价感"
                  - 绝对拒绝空洞的"光波对轰"或低龄化的招式报菜单
                
                【P1-应当】逻辑闭环：
                  - 剧情的发生必须有清晰的前因后果，人物行为需符合逻辑与设定体系
                
                【P1-应当】推进聚焦：
                  - 每段文字必须推进章节目标
                  - 删除任何不推进目标、拖慢节奏的繁冗描写
                
                【P1-应当】结尾悬念（Hook）：
                  - 章节结尾必须卡在情绪高潮、转折点或危机刚露头的地方
                  - 绝对禁止在章末写总结性、完结性的废话
                  - 结尾必须以【具体事件/动作/对话】收束
                  - 这是连载网文，结尾必须留有引人往下看的钩子
                
                ### 输出格式（先计划后成文）
                
                <plan>
                列出 3-6 个叙事节拍，包含：
                - 冲突点 / 转折点 / 情绪推进点
                - 确保覆盖章节目标的达成路径
                （仅写节拍要点，不写理由解释）
                </plan>
                
                <draft>
                中文叙事正文
                - 不包含计划内容
                - 不包含标题或额外说明
                </draft>
                
                ### 输出前自检（内部执行，不输出）
                
                □ 章节目标是否达成？
                □ 是否违反任何禁忌/规则？
                □ 是否出现无证据支撑的新设定？
                □ 角色身份/关系/时间线/地点是否一致？
                □ [TO_CONFIRM] 是否覆盖所有关键不确定点？
                
                ----------------------------------------
                【关键约束重复】
                %s
                """, critical, effectiveGoal, targetWordCount, critical);
    }
    
    /**
     * 提取草稿内容（从 <draft> 标签中，跳过 <plan> 部分）
     */
    private String extractDraftContent(String rawResponse) {
        if (rawResponse == null) return "";

        // 移除 <think> 标签
        String content = THINK_PATTERN.matcher(rawResponse).replaceAll("");

        // 优先提取 <draft>...</draft>
        int start = content.indexOf("<draft>");
        int end = content.indexOf("</draft>");
        if (start != -1) {
            start += 7;
            if (end == -1) end = content.length();
            return content.substring(start, end).trim();
        }

        // 没有 <draft> 标签时，跳过 <plan>...</plan> 部分
        int planEnd = content.indexOf("</plan>");
        if (planEnd != -1) {
            content = content.substring(planEnd + 7).trim();
        }

        return content.trim();
    }
    
    /**
     * 过滤 <think> 标签的状态机
     */
    private static class ThinkFilterState {
        boolean inThink = false;
        String buffer = "";
        String output = "";
    }
    
    /**
     * 过滤 <think> 标签
     */
    private ThinkFilterState filterThinkTag(ThinkFilterState state, String chunk) {
        ThinkFilterState newState = new ThinkFilterState();
        newState.inThink = state.inThink;
        newState.buffer = state.buffer + chunk;
        newState.output = "";
        
        while (!newState.buffer.isEmpty()) {
            if (newState.inThink) {
                int end = newState.buffer.indexOf("</think>");
                if (end == -1) {
                    // 还在 think 块中，清空 buffer
                    newState.buffer = "";
                } else {
                    // think 块结束
                    newState.inThink = false;
                    newState.buffer = newState.buffer.substring(end + 8);
                }
            } else {
                int start = newState.buffer.indexOf("<think>");
                if (start == -1) {
                    // 没有 think 标签，输出全部
                    newState.output += newState.buffer;
                    newState.buffer = "";
                } else {
                    // 输出 think 标签之前的内容
                    newState.output += newState.buffer.substring(0, start);
                    newState.inThink = true;
                    newState.buffer = newState.buffer.substring(start + 7);
                }
            }
        }
        
        return newState;
    }
    
    /**
     * 获取 Writer 系统提示词
     */
    /**
     * 生成预写问题（兼容旧调用，无 projectId 时退化为全量缺口）
     */
    public List<Map<String, String>> generateQuestions(SceneBrief sceneBrief, String chapterGoal,
                                                        List<ChapterSummary> previousSummaries) {
        return generateQuestionsWithProject(null, sceneBrief, chapterGoal, previousSummaries);
    }

    /**
     * 带 projectId 的完整版本 - 支持 gap 证据检索
     */
    public List<Map<String, String>> generateQuestionsWithProject(String projectId, SceneBrief sceneBrief,
                                                                   String chapterGoal,
                                                                   List<ChapterSummary> previousSummaries) {
            if (sceneBrief == null) return new ArrayList<>(DEFAULT_QUESTIONS_ZH);

            // ====================================================================
            // 步骤1: 构建候选缺口（对齐 Python build_gap_items）
            // ====================================================================
            List<Map<String, Object>> candidateGaps = buildGapItems(sceneBrief, chapterGoal);

            // ====================================================================
            // 步骤2: 证据检索 + 充分性判断（对齐 Python _select_unresolved_gaps）
            // ====================================================================
            List<Map<String, Object>> unresolvedGaps;
            if (projectId != null && selectEngine != null && !candidateGaps.isEmpty()) {
                unresolvedGaps = selectUnresolvedGaps(projectId, sceneBrief, chapterGoal, candidateGaps);
            } else {
                // 没有 projectId 或 selectEngine，退化为全部缺口
                unresolvedGaps = candidateGaps;
            }

            // 证据充足，无需提问 → 直接写作
            if (unresolvedGaps.isEmpty()) {
                log.info("WriterAgent: 所有缺口均有证据支撑，跳过预写问题");
                return List.of();
            }

            log.info("WriterAgent: 发现 {} 个未解决缺口，生成预写问题", unresolvedGaps.size());

            // ====================================================================
            // 步骤3: 用 LLM 把缺口转化为具体问题（对齐 Python writer_questions_prompt）
            // ====================================================================
            List<String> contextItems = new ArrayList<>();
            contextItems.add("Chapter: " + sceneBrief.getChapter());
            contextItems.add("Title: " + (sceneBrief.getTitle() != null ? sceneBrief.getTitle() : ""));
            contextItems.add("Goal: " + (sceneBrief.getGoal() != null && !sceneBrief.getGoal().isBlank()
                    ? sceneBrief.getGoal() : chapterGoal));

            if (sceneBrief.getCharacters() != null && !sceneBrief.getCharacters().isEmpty()) {
                List<String> names = sceneBrief.getCharacters().stream()
                        .map(c -> c.getOrDefault("name", "")).filter(n -> !n.isBlank()).toList();
                contextItems.add("Characters: " + String.join(", ", names));
            }

            if (previousSummaries != null && !previousSummaries.isEmpty()) {
                contextItems.add("事实摘要（节选，供反问参考）：");
                for (ChapterSummary s : previousSummaries.stream().limit(3).toList()) {
                    StringBuilder block = new StringBuilder("- " + s.getChapter());
                    if (s.getTitle() != null && !s.getTitle().isBlank()) block.append(" ").append(s.getTitle());
                    if (s.getBriefSummary() != null && !s.getBriefSummary().isBlank())
                        block.append("\n摘要：").append(s.getBriefSummary());
                    contextItems.add(block.toString());
                }
            }

            // 把未解决缺口也放进上下文，让 LLM 知道要针对哪些点提问
            StringBuilder gapText = new StringBuilder("需要确认的信息缺口：\n");
            for (Map<String, Object> gap : unresolvedGaps) {
                gapText.append("- [").append(gap.get("kind")).append("] ").append(gap.get("text")).append("\n");
            }
            contextItems.add(gapText.toString());

            String systemPrompt = """
                    你是 Writer 的「信息缺口分析器」，负责在正式写作前识别关键信息缺口。

                    核心任务：针对上下文中列出的【需要确认的信息缺口】，将每个缺口转化为一个具体的确认问题。

                    问题设计原则：
                    - 聚焦性：每个问题只问一个具体点
                    - 可答性：用户可用一句话回答（避免开放式大问题）
                    - 选项化：优先提供 2-3 个备选项（A/B/C），降低回答门槛

                    优先询问的方向：
                    1. 边界确认：必须发生 / 必须避免的事
                    2. 情绪锚定：情绪强度、转折点、落点
                    3. 关键决策：角色选择、事件走向

                    【P0-必须】禁止泛问题：「你想怎么写」「还有什么补充」「随便你决定」
                    【P0-必须】禁止复述：不要在问题中大段引用上下文
                    【P0-必须】信息已在证据包中明确给出时，不再重复询问

                    严格输出 JSON 数组（1-3 项），不要有任何额外文字。
                    """;

            String userPrompt = """
                    ### 输出格式规范

                    输出 JSON 数组，1-3 项。每项结构：
                    {"type": "问题类型", "text": "问题文本"}

                    type 可选值：
                      - plot_point: 剧情节点相关
                      - character_change: 角色状态/情绪变化相关
                      - detail_gap: 具体细节缺失

                    ### 高质量问题示例（学习格式和思路，不要照抄内容）

                    [
                      {
                        "type": "plot_point",
                        "text": "本章结尾的情绪落点更偏向：A.告别的伤感 / B.冲突的紧张 / C.和解的释然？"
                      },
                      {
                        "type": "character_change",
                        "text": "主角此刻对配角的态度变化程度：A.轻微软化 / B.明显转变 / C.保持原状？"
                      },
                      {
                        "type": "detail_gap",
                        "text": "关键对话发生的场景：A.室内私密空间 / B.户外开放环境 / C.沿用上一章场景？"
                      }
                    ]

                    ### 开始输出
                    直接输出 JSON 数组（不要代码块包裹）：
                    """;

            try {
                List<Map<String, String>> messages = buildMessages(systemPrompt, userPrompt, contextItems);
                LlmResponse response = callLlm(messages);
                String raw = response.getContent();

                String json = extractJsonArray(raw);
                if (json != null) {
                    JsonNode arr = objectMapper.readTree(json);
                    if (arr.isArray() && arr.size() >= 1) {
                        List<Map<String, String>> questions = new ArrayList<>();
                        for (JsonNode item : arr) {
                            String type = item.path("type").asText("").trim();
                            String text = item.path("text").asText("").trim();
                            if (!type.isBlank() && !text.isBlank()) {
                                questions.add(Map.of("type", type, "text", text));
                            }
                        }
                        if (!questions.isEmpty()) {
                            log.info("WriterAgent: 生成了 {} 个预写问题", questions.size());
                            return questions;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("WriterAgent: 预写问题生成失败，使用缺口文本作为问题: {}", e.getMessage());
            }

            // LLM 失败时，直接把缺口文本转成问题（比固定默认问题更有针对性）
            return unresolvedGaps.stream()
                    .limit(3)
                    .map(gap -> Map.of(
                            "type", (String) gap.getOrDefault("kind", "detail_gap"),
                            "text", (String) gap.getOrDefault("text", "")
                    ))
                    .filter(q -> !q.get("text").isBlank())
                    .collect(Collectors.toList());
    }

    /**
     * 构建候选缺口列表（对齐 Python WorkingMemoryService.build_gap_items）
     * 根据 SceneBrief 的完整度判断哪些字段是信息缺口
     */
    private List<Map<String, Object>> buildGapItems(SceneBrief sceneBrief, String chapterGoal) {
        List<Map<String, Object>> gaps = new ArrayList<>();

        String goalText = chapterGoal != null ? chapterGoal.strip() : "";
        String briefGoal = sceneBrief.getGoal() != null ? sceneBrief.getGoal().strip() : "";
        String effectiveGoal = !goalText.isBlank() ? goalText : briefGoal;

        // 章节目标缺口（始终存在，plot_point 类型）
        if (!effectiveGoal.isBlank()) {
            gaps.add(Map.of(
                    "kind", "plot_point",
                    "text", "围绕章节目标的关键推进点是什么（避免偏离目标）",
                    "queries", List.of(effectiveGoal),
                    "ask_user", true
            ));
        }

        // 角色缺口
        List<Map<String, String>> characters = sceneBrief.getCharacters();
        if (characters == null || characters.isEmpty()) {
            gaps.add(Map.of(
                    "kind", "detail_gap",
                    "text", "本章涉及的主要角色有哪些",
                    "queries", List.of("角色 人物 参与"),
                    "ask_user", true
            ));
        } else {
            for (Map<String, String> ch : characters.stream().limit(2).toList()) {
                String name = ch.getOrDefault("name", "").strip();
                if (!name.isBlank()) {
                    gaps.add(Map.of(
                            "kind", "character_change",
                            "text", name + " 在本章的动机/状态是否有变化",
                            "queries", List.of(name + " 动机", name + " 状态"),
                            "ask_user", true,
                            "entity_name", name
                    ));
                }
            }
        }

        // 时间线缺口
        Map<String, String> timeline = sceneBrief.getTimelineContext();
        if (timeline == null || timeline.isEmpty() ||
                (isBlankOrNull(timeline.get("before")) && isBlankOrNull(timeline.get("current")))) {
            gaps.add(Map.of(
                    "kind", "detail_gap",
                    "text", "本章时间/地点的具体边界是什么",
                    "queries", List.of("时间 地点 场景"),
                    "ask_user", true
            ));
        }

        // 世界约束缺口
        if (sceneBrief.getWorldConstraints() == null || sceneBrief.getWorldConstraints().isEmpty()) {
            gaps.add(Map.of(
                    "kind", "plot_point",
                    "text", "本章需遵守的世界规则/禁忌/代价有哪些",
                    "queries", List.of("规则 禁忌 代价 限制"),
                    "ask_user", true
            ));
        }

        // 事实缺口
        if (sceneBrief.getFacts() == null || sceneBrief.getFacts().isEmpty()) {
            gaps.add(Map.of(
                    "kind", "detail_gap",
                    "text", "与本章目标直接相关的已确立事实有哪些",
                    "queries", List.of("关键事实 已确立事实"),
                    "ask_user", true
            ));
        }

        return gaps;
    }

    /**
     * 过滤出证据不足的缺口（对齐 Python _select_unresolved_gaps）
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> selectUnresolvedGaps(String projectId, SceneBrief sceneBrief,
                                                             String chapterGoal,
                                                             List<Map<String, Object>> gaps) {
        List<Map<String, Object>> unresolved = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (Map<String, Object> gap : gaps) {
            if (!Boolean.TRUE.equals(gap.get("ask_user"))) continue;

            String text = (String) gap.getOrDefault("text", "");
            String kind = (String) gap.getOrDefault("kind", "");
            if (text.isBlank() || seen.contains(text)) continue;

            List<String> queries = (List<String>) gap.getOrDefault("queries", List.of());
            if (queries.isEmpty()) {
                unresolved.add(gap);
                seen.add(text);
                continue;
            }

            // 用 retrievalSelect 检索证据
            String combinedQuery = String.join(" ", queries);
            List<ContextItem> evidence = selectEngine.retrievalSelect(
                    projectId, combinedQuery,
                    List.of("character", "world", "fact"), 5
            );

            // 判断是否有足够证据支撑这个缺口
            boolean supported = evidence.stream()
                    .anyMatch(item -> item.getRelevanceScore() >= MIN_GAP_SUPPORT_SCORE);

            if (!supported) {
                // character_change 类型：如果场景简报里已有该角色的 relevant_traits，视为已支撑
                if ("character_change".equals(kind)) {
                    String entityName = (String) gap.getOrDefault("entity_name", "");
                    boolean hasTraits = sceneBrief.getCharacters() != null &&
                            sceneBrief.getCharacters().stream()
                                    .anyMatch(c -> entityName.equals(c.getOrDefault("name", "")) &&
                                            !isBlankOrNull(c.getOrDefault("relevant_traits", "")));
                    if (hasTraits) {
                        log.debug("WriterAgent: gap '{}' 已有 relevant_traits，跳过", text);
                        continue;
                    }
                }
                unresolved.add(gap);
                seen.add(text);
            } else {
                log.debug("WriterAgent: gap '{}' 有证据支撑 (score >= {}), 跳过", text, MIN_GAP_SUPPORT_SCORE);
            }

            if (unresolved.size() >= 3) break;
        }

        return unresolved;
    }

    private boolean isBlankOrNull(String s) {
        return s == null || s.isBlank();
    }

    private String extractJsonArray(String text) {
        if (text == null) return null;
        // 尝试 ```json ... ```
        java.util.regex.Matcher m = Pattern.compile("```(?:json)?\\s*\\n?([\\s\\S]*?)```",
                Pattern.CASE_INSENSITIVE).matcher(text);
        if (m.find()) return m.group(1).trim();
        // 直接找 [ ... ]
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) return text.substring(start, end + 1);
        return null;
    }

    private String getWriterSystemPrompt() {
        return """
                ### 角色定位
                你是 WenShape 系统的 Writer（主笔），一位拥有丰富长篇小说创作经验的专业作家。
                核心职责：将【章节目标】与【证据包】转化为高质量的中文叙事正文。
                
                ### 专业能力
                - 擅长：情节构建、人物刻画、场景描写、对话设计、情绪节奏把控
                - 工作方式：严格基于证据写作，用细节支撑叙事，用留白处理不确定性
                
                ==================================================
                ### 优先级层次（由高到低，冲突时高优先级覆盖低优先级）
                ==================================================
                
                【P0-必须】层级1 - 用户指令与章节目标
                  用户明确要求的内容、章节需要达成的核心目标
                
                【P0-必须】层级2 - 禁忌/规则/代价（FORBIDDEN）
                  世界观硬约束、角色能力边界、不可违背的设定
                
                【P1-应当】层级3 - 证据包内容
                  working_memory > text_chunks > facts > cards > summaries
                
                ==================================================
                ### 反幻觉核心机制
                ==================================================
                
                【P0-必须】证据约束：
                  - 所有叙述必须基于提供的证据包（facts/summaries/cards/text_chunks/working_memory）
                  - 缺乏证据支撑的细节必须使用 [TO_CONFIRM:具体内容] 标记
                  - 绝对禁止为填充内容而编造细节
                
                【P0-必须】身份区分：
                  - 不同名字默认为不同人物
                  - 仅当角色卡 aliases 字段明确列出时才可视为同一人
                
                【P0-必须】输出规范：
                  - 输出语言：中文叙事正文
                  - 禁止输出：思维过程、推理步骤、元说明
                
                ==================================================
                ### 核心文风禁令（首尾重复，必须遵守）
                ==================================================
                
                【P0-必须】禁用高频 AI 词汇：
                  【情绪类】不禁、油然而生、涌上心头、萦绕、涌现、莫名、不由得、不由自主、心头一紧
                  【描写类】深邃、清澈、明亮、闪烁、流转、弥漫、笼罩、蔓延、渗透、交织、氤氲
                  【副词类】缓缓、徐徐、悄然、静静、默默、轻轻、慢慢、渐渐、悄悄、隐隐
                  【连接类】与此同时、不仅如此、除此之外、值得注意的是、不得不说
                  【感叹类】令人、使人、让人不禁、不得不承认
                
                【P0-必须】反总结腔/反 AI 味：
                  - 禁止作者旁白总结、下定义、复盘、升华（尤其段尾）
                  - 禁止句式：他知道/他明白/这意味着/总之/或者说/换句话说/归根结底/可以说/显然
                  - 判断和结论必须落在动作、对话、具体线索上，让读者自己推导
                
                【P0-必须】标点自然化：
                  - 破折号禁止滥用，改用句号拆句或省略号
                  - 一句话不超过2个逗号
                
                ----------------------------------------
                ### 写作质量标准（网文特化）
                
                【P0-必须】视角与沉浸感：
                  - 视角跟随主角叙事，拒绝上帝全知视角旁观
                  - 把环境融入主角的真实体验，不要干瘪的地名/背景介绍
                
                【P0-必须】排版与节奏：
                  - 强制网文短段落，每段最多2-3句
                  - 对白必须独立成段，禁止数百字大段堆砌
                
                【P0-必须】禁止回顾/复述设定：
                  - 禁止叙述腔或内心独白直接解释世界观、能力、身份背景
                  - × 「这便是[身份/群体]，[解释性描述]……」
                  - × 「他深知/他清楚，[设定内容]……」
                  - 设定只能通过事件、感受、对话体现
                
                【P0-必须】禁止「动作后立刻解释」（含两种变体）：
                  变体A - 动作+心理补丁：每个动作后紧跟背景解释或结论
                  × 「他停下。这里是他用三个月任务换来的落脚点。」
                  × 「他皱眉。这意味着情况比预想的更糟。」
                  变体B - 设定触发后立刻完整给出所有代价（铁律公式）
                  ✓ 允许纯动作连续出现，副作用可延迟/被忽略/不完整
                
                【P0-必须】禁止模块化叙事 + 线性空间扫描：
                  - 禁止在一章内把「困境→金手指→代价→外部威胁→下章预告」全走完
                  - 禁止按「中央→左→右→最深处」顺序逐一介绍场景元素
                  × 「主室有石床。左侧是储物室。右侧是药园。最深处有水潭。」
                  ✓ 注意力跳跃：先被某个细节吸引，忽略其他，再被另一个细节打断
                  ✓ 节奏有松弛，允许某些段落什么都没发生
                  - 禁止用「明日/明天，必须去做[X]」作为章节收尾预告句式
                
                【P0-必须】禁止反模板化写法：
                  - 能力描写不要写成游戏脚本状态播报，要写生理感受
                  × 「[能力名]全力运转。」→ ✓ 「他眯起眼。眼球发胀，太阳穴突突直跳。」
                  - 同一能力的副作用每次体感/严重程度/反应要有变化，禁止公式化套用
                  - 禁止递进词（这才/方才/终于）铺垫动作，直接写动作
                
                【P1-应当】口语化要渗透句子结构，不要「俗皮雅骨」：
                  × 「他眉头微皱，心中暗道，娘的，这灵脉废了。」（书面骨架+口语插件）
                  ✓ 「灵脉废了。他早猜到了，就是不想承认。」（结构本身是口语的）
                
                【P1-应当】禁止排比/递进堆砌 + 公约数比喻：
                  - 禁止连续三个以上结构相同的句子
                  - 禁止「像[常见自然现象/动物/睡眠状态]一样」的标准比喻
                  × 「像头沉睡的巨兽」「像熬了三天没睡」
                  ✓ 比喻要私人具体甚至有点怪，宁可不用也别用公约数比喻
                
                【P0-必须】增加人性化细节：
                  - 加入犹豫、杂念、不必要的观察，不要每句话都在推进剧情
                  - 拟声词不要总用最常见的，尝试用比喻或具体描述
                
                ### 输出禁忌
                
                【P0-必须】禁止正文出现系统词汇：证据、检索、数据库、工作记忆、卡片、facts、chunks
                【P1-应当】plan 标签仅用于节拍规划，不用于解释理由
                
                ----------------------------------------
                【关键约束重复 - 请务必遵守】
                【P0-必须】禁用高频 AI 词汇（不禁/油然而生/涌上心头/萦绕/缓缓/徐徐/悄然/与此同时）
                【P0-必须】反总结腔：禁止"他知道/他明白/这意味着/总之/归根结底"
                【P0-必须】网文短段落，对白独立成段
                【P0-必须】证据约束，缺乏证据的细节标记 [TO_CONFIRM]
                【P0-必须】禁止模块化叙事，禁止线性空间扫描，禁止动作后立刻解释
                """;
    }       
}
