package com.wenshape.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenshape.llm.LlmGateway;
import com.wenshape.llm.LlmResponse;
import com.wenshape.model.entity.Draft;
import com.wenshape.model.entity.Issue;
import com.wenshape.model.entity.ReviewResult;
import com.wenshape.storage.CardStorage;
import com.wenshape.storage.DraftStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 编辑 Agent - 修订草稿
 */
@Slf4j
@Component
public class EditorAgent extends BaseAgent {
    
    private static final Pattern THINK_PATTERN = Pattern.compile("<think>.*?</think>", Pattern.DOTALL);
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    public EditorAgent(LlmGateway llmGateway, CardStorage cardStorage, DraftStorage draftStorage) {
        super(llmGateway, cardStorage, draftStorage, "zh");
    }
    
    @Override
    public String getAgentName() {
        return "editor";
    }
    
    @Override
    public String getSystemPrompt() {
        return """
                ### 角色定位
                你是 WenShape 系统的 Editor（编辑），一位经验丰富的文字修订专家。
                核心职责：根据反馈对原稿进行精准修订，保持最小改动原则。
                
                ### 专业能力
                - 擅长：精准修订、风格统一、细节把控、一致性维护
                - 工作原则：只改必改之处，保留原作神韵
                
                ==================================================
                ### 核心约束（最小改动原则）
                ==================================================
                
                【P0-必须】执行力：
                  - 必须 100% 执行用户的修改意见
                  - 改动必须可见、可验证
                
                【P0-必须】保守性：
                  - 未被提及的段落/句子必须逐字保持
                  - 禁止无故换词、调整语序、改标点、改分段
                  - 禁止「顺手润色」「全篇重写」（除非用户明确要求）
                  - 绝对禁止随意将原稿的多个短段落合并成一个大段落！保持网文短句排版
                
                【P0-必须】一致性：
                  - 禁止新增设定/剧情/人物
                  - 禁止引入与原稿矛盾的事实
                  - 保持原文文风、语气、专名/称谓一致
                
                【P0-必须】输出规范：
                  - 仅输出修改后的正文（中文）
                  - 禁止附加解释、说明、修改记录
                
                ────────────────────────────────────────
                ### 编辑策略矩阵
                
                | 反馈类型 | 处理策略 |
                |---------|---------|
                | 局部修正 | 只动相关句段，其他逐字保留 |
                | 风格调整 | 全文统一调整节奏/措辞，但不改事实 |
                | 扩写要求 | 在最相关位置插入，不重排原文结构 |
                | 删减要求 | 精准删除指定内容，保持上下文连贯 |
                | 被拒绝概念 | 必须删除或彻底改写相关表达 |
                
                ### 自检清单（内部执行）
                
                □ 用户的每一条修改要求是否都已执行？
                □ 是否存在过度改动（改了不该改的地方）？
                □ 是否引入了新信息或新矛盾？
                □ 专名、称谓是否保持一致？
                □ 文风和语气是否与原文统一？
                
                ────────────────────────────────────────
                【关键约束重复 - 请务必遵守】
                【P0-必须】100% 执行用户修改意见，改动必须可见
                【P0-必须】未被提及的内容逐字保持，禁止顺手润色
                【P0-必须】禁止合并短段落，保持网文短句排版
                【P0-必须】仅输出修改后的完整正文，禁止附加任何说明
                """;
    }
    
    /**
     * 执行编辑任务 - 修订草稿
     */
    public Map<String, Object> execute(String projectId, String chapter, Map<String, Object> context) {
        String draftVersion = (String) context.getOrDefault("draft_version", "current");
        String userFeedback = (String) context.getOrDefault("user_feedback", "");
        @SuppressWarnings("unchecked")
        List<String> rejectedEntities = (List<String>) context.getOrDefault("rejected_entities", List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> memoryPack = (Map<String, Object>) context.get("memory_pack");
        
        try {
            // 1. 获取当前草稿
            Optional<Draft> draftOpt = draftStorage.getDraft(projectId, chapter, draftVersion);
            if (draftOpt.isEmpty()) {
                return Map.of("success", false, "error", "Draft not found");
            }
            
            Draft currentDraft = draftOpt.get();
            
            // 2. 构建消息
            List<Map<String, String>> messages = buildRevisionMessages(
                    projectId, currentDraft.getContent(), userFeedback, rejectedEntities, memoryPack
            );
            
            // 3. 调用 LLM
            LlmResponse response = callLlm(messages);
            String revisedContent = extractRevisedContent(response.getContent());

            if (normalizeForCompare(revisedContent).equals(normalizeForCompare(currentDraft.getContent()))) {
                revisedContent = attemptQuotedSelectionFallback(
                        projectId,
                        currentDraft.getContent(),
                        userFeedback,
                        rejectedEntities,
                        memoryPack
                );
            }
            
            // 4. 保存新版本
            int newVersionNum = extractVersionNumber(draftVersion) + 1;
            String newVersion = "v" + newVersionNum;
            
            Draft newDraft = draftStorage.saveCurrentDraft(
                    projectId, chapter, revisedContent, revisedContent.length(), null, true
            );
            newDraft.setVersion(newVersion);
            
            return Map.of(
                    "success", true,
                    "draft", newDraft,
                    "version", newVersion
            );
        } catch (Exception e) {
            log.error("编辑执行失败", e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }
    
    /**
     * 建议修订（不保存）- 全文修订
     */
    public String suggestRevision(String projectId, String originalDraft, String userFeedback,
                                   List<String> rejectedEntities) {
        return suggestRevision(projectId, originalDraft, userFeedback, rejectedEntities, null);
    }

    public String suggestRevision(String projectId, String originalDraft, String userFeedback,
                                  List<String> rejectedEntities, Map<String, Object> memoryPack) {
        List<Map<String, String>> messages = buildRevisionMessages(
                projectId, originalDraft, userFeedback, rejectedEntities, memoryPack
        );
        LlmResponse response = callLlm(messages);
        String revised = extractRevisedContent(response.getContent());
        if (normalizeForCompare(revised).equals(normalizeForCompare(originalDraft))) {
            return attemptQuotedSelectionFallback(projectId, originalDraft, userFeedback, rejectedEntities, memoryPack);
        }
        return revised;
    }

    /**
     * 选区编辑（子串匹配方式）- 对齐 Python suggest_revision_selection
     */
    public String suggestRevisionSelection(String projectId, String originalDraft,
                                            String selectionText, String userFeedback,
                                            List<String> rejectedEntities) {
        return suggestRevisionSelection(projectId, originalDraft, selectionText, userFeedback, rejectedEntities, null);
    }

    public String suggestRevisionSelection(String projectId, String originalDraft,
                                           String selectionText, String userFeedback,
                                           List<String> rejectedEntities, Map<String, Object> memoryPack) {
        if (selectionText == null || selectionText.isBlank()) {
            throw new IllegalArgumentException("选区编辑失败：选区为空");
        }
        String original = normalizeNewlines(originalDraft);
        String sel = normalizeNewlines(selectionText);
        int pos = original.indexOf(sel);
        if (pos < 0) {
            throw new IllegalArgumentException("选区编辑失败：未在正文中找到选区文本（请重新选中或缩小选区）");
        }
        return suggestRevisionSelectionRange(projectId, original, pos, pos + sel.length(),
                sel, userFeedback, rejectedEntities, memoryPack);
    }

    /**
     * 选区编辑（精确偏移方式）- 对齐 Python suggest_revision_selection_range
     */
    public String suggestRevisionSelectionRange(String projectId, String originalDraft,
                                                 int selectionStart, int selectionEnd,
                                                 String selectionText, String userFeedback,
                                                 List<String> rejectedEntities) {
        return suggestRevisionSelectionRange(projectId, originalDraft, selectionStart, selectionEnd,
                selectionText, userFeedback, rejectedEntities, null);
    }

    public String suggestRevisionSelectionRange(String projectId, String originalDraft,
                                                int selectionStart, int selectionEnd,
                                                String selectionText, String userFeedback,
                                                List<String> rejectedEntities, Map<String, Object> memoryPack) {
        String original = normalizeNewlines(originalDraft);
        int start = Math.max(0, Math.min(selectionStart, original.length()));
        int end = Math.max(0, Math.min(selectionEnd, original.length()));
        if (end <= start) {
            throw new IllegalArgumentException("选区编辑失败：选区为空或无效");
        }
        if (end - start > 3200) {
            throw new IllegalArgumentException("选区编辑失败：选区过长（建议 ≤3200 字符），请缩小选区后再试");
        }
        String sel = original.substring(start, end);
        // 校验前端传来的 selectionText 是否与实际选区一致
        if (selectionText != null && !selectionText.isBlank()) {
            String provided = normalizeNewlines(selectionText);
            if (!provided.equals(sel)) {
                throw new IllegalArgumentException("选区编辑失败：选区内容已变化（请重新选中后再试）");
            }
        }

        String prefixHint = original.substring(Math.max(0, start - 220), start);
        String suffixHint = original.substring(end, Math.min(original.length(), end + 220));

        List<String> contextItems = new ArrayList<>();
        if (rejectedEntities != null && !rejectedEntities.isEmpty()) {
            contextItems.add("被拒绝概念：" + String.join(", ", rejectedEntities) +
                    "\n【P0-必须】必须删除或彻底改写所有被标记为[拒绝]的概念");
        }
        cardStorage.getStyleCard(projectId).ifPresent(styleCard -> {
            if (styleCard.getStyle() != null && !styleCard.getStyle().isBlank()) {
                contextItems.add("文风要求：\n" + styleCard.getStyle());
            }
        });
        if (memoryPack != null) {
            contextItems.addAll(formatMemoryPackContext(memoryPack));
        }

        String systemPrompt = getSystemPrompt();
        String userPrompt = String.format("""
                ### 选区编辑任务
                
                你只需要改写【选区文本】，输出替换后的选区内容（不含前后文）。
                
                【上文参考（不输出）】
                %s
                
                【选区文本（需改写）】
                <<<SELECTION_START>>>
                %s
                <<<SELECTION_END>>>
                
                【下文参考（不输出）】
                %s
                
                ### 用户指令
                %s
                
                ### 输出要求
                【P0-必须】只输出改写后的选区文本，不含上下文
                【P0-必须】改动必须可见，不得与原选区完全相同
                【P0-必须】保持与上下文的衔接自然
                【P0-必须】禁止附加任何解释或说明
                """, prefixHint, sel, suffixHint, userFeedback);

        List<Map<String, String>> messages = buildMessages(systemPrompt, userPrompt, contextItems);
        LlmResponse response = callLlm(messages);
        String replacement = normalizeNewlines(response.getContent() != null ? response.getContent() : "").strip();

        if (replacement.isBlank()) {
            throw new IllegalArgumentException("选区编辑失败：模型未生成替换文本（请缩小选区并更具体描述修改）");
        }

        String revised = (original.substring(0, start) + replacement + original.substring(end)).stripTrailing();

        // 如果结果与原文完全相同，重试一次
        if (normalizeForCompare(revised).equals(normalizeForCompare(original))) {
            List<Map<String, String>> retryMessages = new ArrayList<>(messages);
            retryMessages.add(Map.of("role", "assistant", "content", response.getContent() != null ? response.getContent() : ""));
            retryMessages.add(Map.of("role", "user", "content",
                    "你刚才输出的替换文本未产生任何可见修改。\n请重新输出「替换后的选区文本」，并确保：\n- 必须与选区原文不同\n- 严格执行用户反馈\n现在重新输出："));
            LlmResponse response2 = callLlm(retryMessages);
            String replacement2 = normalizeNewlines(response2.getContent() != null ? response2.getContent() : "").strip();
            if (replacement2.isBlank()) {
                throw new IllegalArgumentException("选区编辑失败：模型未生成替换文本（重试后）");
            }
            String revised2 = (original.substring(0, start) + replacement2 + original.substring(end)).stripTrailing();
            if (normalizeForCompare(revised2).equals(normalizeForCompare(original))) {
                throw new IllegalArgumentException("选区编辑失败：未能生成可应用差异（请缩小选区并更具体描述修改）");
            }
            return revised2;
        }
        return revised;
    }

    /**
     * 去 AI 味重写 - 对齐 Python rewrite_text
     */
    public String rewriteText(String projectId, String originalText) {
        if (originalText == null || originalText.isBlank()) {
            return "";
        }
        String systemPrompt = """
                你是一位深谙网络文学创作的文字改写专家，擅长将生硬、板正的 AI 腔调文字转化为自然流畅、充满人情味的网文风格。
                
                ### 改写目标
                - 彻底消除 AI 腔：去掉"不禁"、"顿时"、"霎时"、"不由得"等套话
                - 口语化、短句化：多用短句，增加节奏感
                - 保留原意：不改变核心情节和人物行为
                - 网文排版：保持短段落，不合并段落
                
                ### 输出要求
                【P0-必须】只输出改写后的正文，禁止附加任何解释
                【P0-必须】保持网文短句排版，禁止合并段落
                """;
        String userPrompt = "请将以下文字进行去 AI 味改写：\n\n" + originalText;
        List<Map<String, String>> messages = buildMessages(systemPrompt, userPrompt, List.of());
        LlmResponse response = callLlm(messages);
        String result = THINK_PATTERN.matcher(response.getContent() != null ? response.getContent() : "").replaceAll("").strip();
        return result;
    }

    // ========== 工具方法 ==========

    private String normalizeNewlines(String text) {
        if (text == null) return "";
        return text.replace("\r\n", "\n").replace("\r", "\n");
    }

    private String normalizeForCompare(String text) {
        if (text == null) return "";
        return text.replaceAll("\\s+", " ").trim();
    }
    
    private List<Map<String, String>> buildRevisionMessages(String projectId, String originalDraft, String userFeedback,
                                                             List<String> rejectedEntities,
                                                             Map<String, Object> memoryPack) {
        List<String> contextItems = new ArrayList<>();

        // 原始草稿
        contextItems.add("<<<DRAFT_START>>>\n" + originalDraft + "\n<<<DRAFT_END>>>");

        // 被否决的设定
        if (rejectedEntities != null && !rejectedEntities.isEmpty()) {
            contextItems.add("【P0-必须】被拒绝概念处理：必须删除或彻底改写所有被标记为[拒绝]的概念，确保最终稿中完全不出现。\n被否决的设定：\n- " +
                    String.join("\n- ", rejectedEntities));
        }

        String styleProjectId = (projectId != null && !projectId.isBlank()) ? projectId : projectIdFromMemory(memoryPack);
        cardStorage.getStyleCard(styleProjectId).ifPresent(styleCard -> {
            if (styleCard.getStyle() != null && !styleCard.getStyle().isBlank()) {
                contextItems.add("文风要求：\n" + styleCard.getStyle());
            }
        });
        if (memoryPack != null) {
            contextItems.addAll(formatMemoryPackContext(memoryPack));
        }

        String critical = String.format("""
                ==================================================
                ### 修订任务
                ==================================================
                
                根据【用户反馈】修订【原稿】
                
                ### 执行规则
                
                【P0-必须】完整执行：
                  - 用户的每一条修改意见都必须执行
                  - 改动必须可见、可验证
                
                【P0-必须】极简修改（Minimal Edits）：
                  - 仅替换/插入真正需要修改的句子，不重写边缘上下文
                  - 原文的留白、短句短段必须保持原样，绝不可擅自合并段落
                
                【P0-必须】最小改动：
                  - 未被提及的内容必须保持不变
                  - 禁止无故换词、调整语序、改标点、改分段
                  - 绝对禁止随意将原稿的多个短段落合并成一个大段落！保持网文短句排版
                  - 禁止「顺手润色」
                
                【P0-必须】信息保真：
                  - 禁止新增设定/剧情/人物
                  - 禁止引入与原稿矛盾的事实
                
                【P0-必须】风格一致：
                  - 保持原文文风与语气
                  - 保持专名/称谓一致
                
                ### 输出要求
                
                【P0-必须】仅输出修改后的完整正文（中文）
                【P0-必须】禁止添加解释、说明、修改记录
                【P0-必须】输出末尾必须以单独一行结束标记收尾：<<<REVISED_DRAFT_END>>>
                【P0-必须】标记之后不得再输出任何字符
                """);

        String userPrompt = String.format("""
                %s
                
                ### 用户反馈（需执行的修改）
                
                <<<FEEDBACK_START>>>
                %s
                <<<FEEDBACK_END>>>
                
                ### 开始输出
                请直接输出修改后的完整正文，并在最后一行输出结束标记：
                <<<REVISED_DRAFT_END>>>
                
                ────────────────────────────────────────
                【修订规则重复】
                %s
                """, critical, userFeedback != null ? userFeedback : "", critical);

        return buildMessages(getSystemPrompt(), userPrompt, contextItems);
    }

    private String projectIdFromMemory(Map<String, Object> memoryPack) {
        if (memoryPack == null) {
            return "";
        }
        return String.valueOf(memoryPack.getOrDefault("project_id", ""));
    }
    
    private String extractRevisedContent(String rawResponse) {
        if (rawResponse == null) return "";

        // 移除 <think> 标签
        String content = THINK_PATTERN.matcher(rawResponse).replaceAll("");

        // 截断结束标记（对齐 Python EDITOR_REVISION_END_MARKER）
        int markerIdx = content.indexOf("<<<REVISED_DRAFT_END>>>");
        if (markerIdx >= 0) {
            content = content.substring(0, markerIdx);
        }

        // 提取 <revised> 或 <draft> 标签内容
        for (String tag : List.of("revised", "draft", "content")) {
            int start = content.indexOf("<" + tag + ">");
            int end = content.indexOf("</" + tag + ">");

            if (start != -1) {
                start += tag.length() + 2;
                if (end == -1) end = content.length();
                return content.substring(start, end).trim();
            }
        }

        return content.trim();
    }
    
    private int extractVersionNumber(String version) {
        if (version == null || version.equals("current")) {
            return 1;
        }
        
        try {
            return Integer.parseInt(version.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private String attemptQuotedSelectionFallback(String projectId, String originalDraft, String userFeedback,
                                                  List<String> rejectedEntities, Map<String, Object> memoryPack) {
        String selection = extractQuotedSelection(userFeedback, originalDraft);
        if (selection == null || selection.isBlank()) {
            throw new IllegalArgumentException("未能生成可应用的差异修改：请在指令中复制粘贴要修改的原句/段落，或使用选区编辑进行精确定位。");
        }
        return suggestRevisionSelection(projectId, originalDraft, selection, userFeedback, rejectedEntities, memoryPack);
    }

    private String extractQuotedSelection(String feedback, String originalDraft) {
        if (feedback == null || feedback.isBlank() || originalDraft == null || originalDraft.isBlank()) {
            return null;
        }
        List<Pattern> patterns = List.of(
                Pattern.compile("[\"“](.+?)[\"”]"),
                Pattern.compile("[「『](.+?)[」』]")
        );
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(feedback);
            while (matcher.find()) {
                String candidate = normalizeNewlines(matcher.group(1)).trim();
                if (!candidate.isBlank() && originalDraft.contains(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    public ReviewResult reviewDraft(String projectId, String chapter, String draftVersion, String draftContent) {
        if (draftContent == null || draftContent.isBlank()) {
            return ReviewResult.builder()
                    .chapter(chapter)
                    .draftVersion(draftVersion)
                    .overallAssessment("草稿为空，无法审阅。")
                    .canProceed(false)
                    .issues(List.of(Issue.builder()
                            .severity("high")
                            .category("content")
                            .location("全文")
                            .problem("当前草稿为空。")
                            .suggestion("请先生成或粘贴正文，再进行审阅。")
                            .build()))
                    .build();
        }

        String systemPrompt = """
                你是一位小说审稿编辑。请审查草稿在剧情推进、人物一致性、表达清晰度上的问题。
                只输出 JSON，不要解释：
                {
                  "issues": [{"severity":"low|medium|high","category":"plot|character|style|clarity|consistency","location":"位置","problem":"问题","suggestion":"建议"}],
                  "overall_assessment": "总体评价",
                  "can_proceed": true
                }
                """;
        String userPrompt = """
                请审阅以下章节草稿，只列出最关键的 0-6 个问题。若整体可继续编辑或定稿，也要明确说明。

                章节：%s
                版本：%s

                正文：
                %s
                """.formatted(chapter, draftVersion, draftContent);

        try {
            LlmResponse response = callLlm(buildMessages(systemPrompt, userPrompt, List.of()));
            Map<String, Object> parsed = parseJsonObject(response.getContent());
            List<Issue> issues = parseIssues(parsed.get("issues"));
            String overall = String.valueOf(parsed.getOrDefault("overall_assessment", "整体可读，但建议结合问题项再检查一轮。"));
            boolean canProceed = !(parsed.get("can_proceed") instanceof Boolean b) || b;
            return ReviewResult.builder()
                    .chapter(chapter)
                    .draftVersion(draftVersion)
                    .issues(issues)
                    .overallAssessment(overall)
                    .canProceed(canProceed)
                    .build();
        } catch (Exception e) {
            log.warn("reviewDraft failed, using heuristic fallback: {}", e.getMessage());
            List<Issue> issues = new ArrayList<>();
            if (draftContent.length() < 800) {
                issues.add(Issue.builder()
                        .severity("medium")
                        .category("plot")
                        .location("全文")
                        .problem("章节篇幅偏短，可能未充分完成场景推进。")
                        .suggestion("检查是否补足冲突推进、人物反应和收束段。")
                        .build());
            }
            return ReviewResult.builder()
                    .chapter(chapter)
                    .draftVersion(draftVersion)
                    .issues(issues)
                    .overallAssessment(issues.isEmpty() ? "未发现明显结构性问题，可继续后续流程。" : "存在少量需要复核的问题，建议修改后再确认。")
                    .canProceed(issues.size() <= 1)
                    .build();
        }
    }

    private Map<String, Object> parseJsonObject(String raw) throws Exception {
        String content = THINK_PATTERN.matcher(raw == null ? "" : raw).replaceAll("").trim();
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            content = content.substring(start, end + 1);
        }
        return objectMapper.readValue(content, new TypeReference<>() {});
    }

    private List<Issue> parseIssues(Object rawIssues) {
        List<Issue> issues = new ArrayList<>();
        if (rawIssues instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                issues.add(Issue.builder()
                        .severity(stringValue(map, "severity", "medium"))
                        .category(stringValue(map, "category", "style"))
                        .location(stringValue(map, "location", "全文"))
                        .problem(stringValue(map, "problem", ""))
                        .suggestion(stringValue(map, "suggestion", ""))
                        .build());
            }
        }
        return issues;
    }

    private List<String> formatMemoryPackContext(Map<String, Object> memoryPack) {
        List<String> contextItems = new ArrayList<>();
        if (memoryPack == null || memoryPack.isEmpty()) {
            return contextItems;
        }
        Object payloadObj = memoryPack.getOrDefault("payload", memoryPack);
        if (!(payloadObj instanceof Map<?, ?> payload)) {
            return contextItems;
        }
        Object digestObj = memoryPack.get("chapter_digest");
        if (digestObj instanceof Map<?, ?> digest) {
            List<String> parts = new ArrayList<>();
            Object summary = digest.get("summary");
            if (summary != null && !summary.toString().isBlank()) {
                parts.add("摘要：" + summary);
            }
            Object tail = digest.get("tail_excerpt");
            if (tail != null && !tail.toString().isBlank()) {
                parts.add("结尾片段：\n" + tail);
            }
            if (!parts.isEmpty()) {
                contextItems.add("本章概览：\n" + String.join("\n", parts));
            }
        }

        Object workingMemory = payload.get("working_memory");
        if (workingMemory != null && !workingMemory.toString().isBlank()) {
            contextItems.add("工作记忆：\n" + workingMemory);
        }
        Object unresolved = payload.get("unresolved_gaps");
        if (unresolved instanceof List<?> list && !list.isEmpty()) {
            StringBuilder sb = new StringBuilder("待注意缺口：\n");
            int count = 0;
            for (Object item : list) {
                if (item instanceof Map<?, ?> gap) {
                    String text = stringValue(gap, "text", "");
                    if (!text.isBlank()) {
                        sb.append("- ").append(text).append("\n");
                        if (++count >= 5) break;
                    }
                }
            }
            if (count > 0) {
                contextItems.add(sb.toString());
            }
        }
        Object evidencePack = payload.get("evidence_pack");
        if (evidencePack instanceof Map<?, ?> pack) {
            Object itemsObj = pack.get("items");
            if (itemsObj instanceof List<?> items && !items.isEmpty()) {
                StringBuilder sb = new StringBuilder("关键证据：\n");
                int count = 0;
                for (Object item : items) {
                    if (item instanceof Map<?, ?> ev) {
                        String type = stringValue(ev, "type", "");
                        String text = stringValue(ev, "text", "");
                        if (!text.isBlank()) {
                            sb.append("- ");
                            if (!type.isBlank()) sb.append("[").append(type).append("] ");
                            sb.append(text).append("\n");
                            if (++count >= 8) break;
                        }
                    }
                }
                if (count > 0) {
                    contextItems.add(sb.toString());
                }
            }
        }
        return contextItems;
    }

    private String stringValue(Map<?, ?> map, String key, String fallback) {
        if (map == null) {
            return fallback;
        }
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        return String.valueOf(value);
    }
}
