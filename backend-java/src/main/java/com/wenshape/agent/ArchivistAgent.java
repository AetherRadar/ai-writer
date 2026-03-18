package com.wenshape.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenshape.context.ContextItem;
import com.wenshape.context.ContextSelectEngine;
import com.wenshape.llm.LlmGateway;
import com.wenshape.llm.LlmResponse;
import com.wenshape.model.entity.Fact;
import com.wenshape.model.entity.ChapterSummary;
import com.wenshape.model.entity.SceneBrief;
import com.wenshape.storage.CardStorage;
import com.wenshape.storage.CanonStorage;
import com.wenshape.storage.DraftStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 档案员 Agent - 生成场景简报和管理设定
 */
@Slf4j
@Component
public class ArchivistAgent extends BaseAgent {

    private final ContextSelectEngine selectEngine;
    private final CanonStorage canonStorage;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ArchivistAgent(LlmGateway llmGateway, CardStorage cardStorage, DraftStorage draftStorage,
                          ContextSelectEngine selectEngine, CanonStorage canonStorage) {
        super(llmGateway, cardStorage, draftStorage, "zh");
        this.selectEngine = selectEngine;
        this.canonStorage = canonStorage;
    }

    @Override
    public String getAgentName() {
        return "archivist";
    }

    @Override
    public String getSystemPrompt() {
        return """
                ### 角色定位
                你是 WenShape 系统的 Archivist（资料管理员），一位精通信息结构化的知识工程师。
                核心职责：将文本内容转换为可落库的结构化信息，并为撰稿人准备写作所需的场景简报。
                
                ### 专业能力
                - 擅长：信息抽取、结构化转换、一致性维护、知识图谱构建
                - 输出类型：事实/时间线/角色状态/摘要/设定卡/场景简报
                
                ==================================================
                ### 核心约束（信息保真原则）
                ==================================================
                
                【P0-必须】证据约束：
                  - 仅依据输入内容进行抽取
                  - 禁止捏造任何输入未明确包含的信息
                  - 不确定时：留空 / 空列表 / 降低置信度
                
                【P0-必须】输出格式：
                  - 严格可解析（JSON 或 YAML）
                  - 禁止添加 Markdown 格式、代码块、解释说明
                  - 禁止输出思维过程
                
                【P0-必须】Schema 遵循：
                  - 键名和类型必须与指定 schema 完全匹配
                  - 不添加额外字段，不省略必需字段
                
                ────────────────────────────────────────
                ### 信息抽取策略
                
                【P1-应当】优先抽取（对后文有约束力的信息）：
                  - 规则/禁忌/代价（世界观硬约束）
                  - 关键关系变化
                  - 重要状态转变
                  - 关键事件节点
                
                【P1-应当】避免抽取：
                  - 琐碎重复信息
                  - 推测性内容（推测不能当事实）
                
                【P1-应当】命名一致性：
                  - 使用输入中出现的原名
                  - 禁止擅自改名或翻译
                
                ### 自检清单（内部执行）
                
                □ 输出是否严格符合 schema？
                □ 是否包含多余的说明文字？
                □ 是否存在「输入没有但觉得合理」的捏造？
                
                ────────────────────────────────────────
                【关键约束重复 - 请务必遵守】
                【P0-必须】仅依据输入内容抽取，禁止捏造
                【P0-必须】严格输出 JSON，不添加任何额外文字
                【P0-必须】键名和类型必须与指定 schema 完全匹配
                
                你必须严格按照以下 JSON 格式输出，不要添加任何额外文字：
                {
                  "title": "章节标题",
                  "goal": "章节目标的具体化描述",
                  "characters": [
                    {"name": "角色名", "relevant_traits": "该角色的关键特征和当前状态"}
                  ],
                  "world_constraints": ["世界观约束1", "世界观约束2"],
                  "facts": ["相关事实1", "相关事实2"],
                  "style_reminder": "文风提醒，包括写作风格、语气、节奏等",
                  "timeline_context": {
                    "before": "前文发生了什么（上一章结尾）",
                    "current": "本章需要推进的情节",
                    "after": ""
                  },
                  "forbidden": ["绝对不能违反的禁区1", "禁区2"]
                }
                """;
    }

    /**
     * 执行档案员任务 - 生成场景简报
     */
    public Map<String, Object> execute(String projectId, String chapter, Map<String, Object> context) {
        String chapterTitle = (String) context.getOrDefault("chapter_title", "");
        String chapterGoal = (String) context.getOrDefault("chapter_goal", "");
        @SuppressWarnings("unchecked")
        List<String> characterNames = (List<String>) context.getOrDefault("characters", List.of());

        try {
            // 1. 收集上下文
            List<ContextItem> criticalItems = selectEngine.deterministicSelect(projectId, "archivist");

            // 2. 检索相关设定
            String query = chapterGoal + " " + String.join(" ", characterNames);
            List<ContextItem> relevantItems = selectEngine.retrievalSelect(
                    projectId, query,
                    List.of("character", "world", "fact"),
                    10
            );

            // 3. 获取前文摘要
            List<ChapterSummary> previousSummaries = getPreviousSummaries(projectId, chapter, 3);

            // 4. 获取相关事实
            List<Map<String, Object>> facts = getRelevantFacts(projectId, chapterGoal, characterNames);

            // 5. 构建消息
            List<Map<String, String>> messages = buildSceneBriefMessages(
                    chapter, chapterTitle, chapterGoal, characterNames,
                    criticalItems, relevantItems, previousSummaries, facts
            );

            // 6. 调用 LLM
            LlmResponse response = callLlm(messages);
            log.info("Archivist LLM response length: {}", response.getContent().length());

            // 7. 解析场景简报（JSON 解析）
            SceneBrief sceneBrief = parseSceneBriefFromJson(response.getContent(), chapter, chapterTitle, chapterGoal);

            return Map.of(
                    "success", true,
                    "scene_brief", sceneBrief
            );

        } catch (Exception e) {
            log.error("档案员执行失败", e);
            // 回退：构建基础简报
            SceneBrief fallback = buildFallbackSceneBrief(chapter, chapterTitle, chapterGoal, characterNames);
            return Map.of("success", true, "scene_brief", fallback);
        }
    }

    private List<Map<String, Object>> getRelevantFacts(String projectId, String chapterGoal, List<String> characterNames) {
        try {
            List<com.wenshape.model.entity.Fact> facts = canonStorage.listFacts(projectId);
            List<Map<String, Object>> result = new ArrayList<>();
            for (com.wenshape.model.entity.Fact f : facts) {
                Map<String, Object> m = new HashMap<>();
                m.put("id", f.getId());
                m.put("statement", f.getStatement() != null ? f.getStatement() : f.getContent());
                m.put("content", f.getContent());
                result.add(m);
            }
            return result;
        } catch (Exception e) {
            log.warn("获取事实失败: {}", e.getMessage());
            return List.of();
        }
    }

    private List<ChapterSummary> getPreviousSummaries(String projectId, String chapter, int limit) {
        try {
            List<ChapterSummary> allSummaries = draftStorage.listChapterSummaries(projectId, null);
            List<ChapterSummary> previous = new ArrayList<>();
            for (ChapterSummary summary : allSummaries) {
                if (compareChapters(summary.getChapter(), chapter) < 0) {
                    previous.add(summary);
                }
            }
            int start = Math.max(0, previous.size() - limit);
            return previous.subList(start, previous.size());
        } catch (Exception e) {
            log.warn("获取前文摘要失败: {}", e.getMessage());
            return List.of();
        }
    }

    private int compareChapters(String a, String b) {
        try {
            int[] parsedA = parseChapter(a);
            int[] parsedB = parseChapter(b);
            if (parsedA[0] != parsedB[0]) return Integer.compare(parsedA[0], parsedB[0]);
            return Integer.compare(parsedA[1], parsedB[1]);
        } catch (Exception e) {
            return a.compareTo(b);
        }
    }

    private int[] parseChapter(String chapter) {
        String upper = chapter.toUpperCase();
        int vIdx = upper.indexOf('V');
        int cIdx = upper.indexOf('C');
        if (vIdx >= 0 && cIdx > vIdx) {
            int vol = Integer.parseInt(upper.substring(vIdx + 1, cIdx));
            int ch = Integer.parseInt(upper.substring(cIdx + 1));
            return new int[]{vol, ch};
        }
        return new int[]{1, 1};
    }

    private List<Map<String, String>> buildSceneBriefMessages(
            String chapter, String chapterTitle, String chapterGoal, List<String> characterNames,
            List<ContextItem> criticalItems, List<ContextItem> relevantItems,
            List<ChapterSummary> previousSummaries, List<Map<String, Object>> facts) {

        List<String> contextItems = new ArrayList<>();

        // 前文摘要
        if (!previousSummaries.isEmpty()) {
            StringBuilder sb = new StringBuilder("【前文摘要】\n");
            for (ChapterSummary s : previousSummaries) {
                sb.append("- ").append(s.getChapter()).append(": ").append(s.getBriefSummary()).append("\n");
            }
            contextItems.add(sb.toString());
        }

        // 角色卡片（critical 优先，去重）
        Set<String> addedCharacters = new HashSet<>();
        for (ContextItem item : criticalItems) {
            if ("character_card".equals(item.getType().getValue())) {
                String name = item.getMetadata().getOrDefault("name", item.getId()).toString();
                if (addedCharacters.add(name)) {
                    contextItems.add("【角色】" + name + "\n" + item.getContent());
                }
            }
        }
        for (ContextItem item : relevantItems) {
            if ("character_card".equals(item.getType().getValue())) {
                String name = item.getMetadata().getOrDefault("name", item.getId()).toString();
                if (addedCharacters.add(name)) {
                    contextItems.add("【角色】" + name + "\n" + item.getContent());
                }
            }
        }

        // 世界观卡片（去重）
        Set<String> addedWorlds = new HashSet<>();
        for (ContextItem item : relevantItems) {
            if ("world_card".equals(item.getType().getValue())) {
                String name = item.getMetadata().getOrDefault("name", item.getId()).toString();
                if (addedWorlds.add(name)) {
                    contextItems.add("【世界观】" + name + "\n" + item.getContent());
                }
            }
        }

        // 文风卡
        for (ContextItem item : criticalItems) {
            if ("style_card".equals(item.getType().getValue())) {
                contextItems.add("【文风卡】\n" + item.getContent());
            }
        }

        // 相关事实（最多10条）
        if (!facts.isEmpty()) {
            StringBuilder sb = new StringBuilder("【已知事实】\n");
            int count = 0;
            for (Map<String, Object> fact : facts) {
                String stmt = (String) fact.getOrDefault("statement", fact.getOrDefault("content", ""));
                if (stmt != null && !stmt.isBlank()) {
                    sb.append("- ").append(stmt).append("\n");
                    if (++count >= 10) break;
                }
            }
            contextItems.add(sb.toString());
        }

        String userPrompt = String.format("""
                请为以下章节生成场景简报（严格输出 JSON，不要有任何额外文字）：
                
                章节ID: %s
                章节标题: %s
                章节目标: %s
                涉及角色: %s
                """,
                chapter, chapterTitle, chapterGoal,
                characterNames.isEmpty() ? "未指定" : String.join("、", characterNames)
        );

        return buildMessages(getSystemPrompt(), userPrompt, contextItems);
    }

    /**
     * 从 LLM 输出的 JSON 解析 SceneBrief
     */
    private SceneBrief parseSceneBriefFromJson(String content, String chapter, String defaultTitle, String defaultGoal) {
        SceneBrief brief = new SceneBrief();
        brief.setChapter(chapter);
        brief.setTitle(defaultTitle);
        brief.setGoal(defaultGoal);
        brief.setCharacters(new ArrayList<>());
        brief.setWorldConstraints(new ArrayList<>());
        brief.setFacts(new ArrayList<>());
        brief.setForbidden(new ArrayList<>());
        brief.setTimelineContext(new HashMap<>());

        if (content == null || content.isBlank()) return brief;

        // 提取 JSON 块（可能被 ```json ... ``` 包裹）
        String json = extractJson(content);
        if (json == null) {
            log.warn("Archivist: 无法从 LLM 输出中提取 JSON，原始内容: {}", content.substring(0, Math.min(200, content.length())));
            return brief;
        }

        try {
            JsonNode root = objectMapper.readTree(json);

            // title
            if (root.has("title") && !root.get("title").isNull()) {
                String t = root.get("title").asText("").trim();
                if (!t.isEmpty()) brief.setTitle(t);
            }

            // goal
            if (root.has("goal") && !root.get("goal").isNull()) {
                String g = root.get("goal").asText("").trim();
                if (!g.isEmpty()) brief.setGoal(g);
            }

            // style_reminder
            if (root.has("style_reminder") && !root.get("style_reminder").isNull()) {
                brief.setStyleReminder(root.get("style_reminder").asText("").trim());
            }

            // characters
            if (root.has("characters") && root.get("characters").isArray()) {
                List<Map<String, String>> characters = new ArrayList<>();
                for (JsonNode charNode : root.get("characters")) {
                    Map<String, String> charMap = new HashMap<>();
                    charMap.put("name", charNode.path("name").asText(""));
                    charMap.put("relevant_traits", charNode.path("relevant_traits").asText(""));
                    if (!charMap.get("name").isEmpty()) {
                        characters.add(charMap);
                    }
                }
                brief.setCharacters(characters);
            }

            // world_constraints
            if (root.has("world_constraints") && root.get("world_constraints").isArray()) {
                List<String> wc = new ArrayList<>();
                for (JsonNode n : root.get("world_constraints")) {
                    String s = n.asText("").trim();
                    if (!s.isEmpty()) wc.add(s);
                }
                brief.setWorldConstraints(wc);
            }

            // facts
            if (root.has("facts") && root.get("facts").isArray()) {
                List<String> factList = new ArrayList<>();
                for (JsonNode n : root.get("facts")) {
                    String s = n.asText("").trim();
                    if (!s.isEmpty()) factList.add(s);
                }
                brief.setFacts(factList);
            }

            // forbidden
            if (root.has("forbidden") && root.get("forbidden").isArray()) {
                List<String> forbiddenList = new ArrayList<>();
                for (JsonNode n : root.get("forbidden")) {
                    String s = n.asText("").trim();
                    if (!s.isEmpty()) forbiddenList.add(s);
                }
                brief.setForbidden(forbiddenList);
            }

            // timeline_context
            if (root.has("timeline_context") && root.get("timeline_context").isObject()) {
                Map<String, String> tc = new HashMap<>();
                JsonNode tcNode = root.get("timeline_context");
                tc.put("before", tcNode.path("before").asText(""));
                tc.put("current", tcNode.path("current").asText(""));
                tc.put("after", tcNode.path("after").asText(""));
                brief.setTimelineContext(tc);
            }

            log.info("Archivist: 场景简报解析成功 - title={}, characters={}, facts={}",
                    brief.getTitle(), brief.getCharacters().size(), brief.getFacts().size());

        } catch (Exception e) {
            log.error("Archivist: JSON 解析失败: {}", e.getMessage());
        }

        return brief;
    }

    /**
     * 从文本中提取 JSON 块
     */
    private String extractJson(String text) {
        if (text == null) return null;

        // 尝试 ```json ... ``` 代码块
        Pattern codeBlock = Pattern.compile("```(?:json)?\\s*\\n?([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
        Matcher m = codeBlock.matcher(text);
        if (m.find()) {
            return m.group(1).trim();
        }

        // 尝试直接找 { ... }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }

        return null;
    }

    /**
     * 纯启发式检测草稿中的新设定建议（对齐 Python detect_setting_changes，无 LLM 调用）
     * 按产品需求：过滤掉 Character 类型，只保留 World 类型建议
     */
    public List<Map<String, Object>> detectSettingChanges(String draftContent, List<String> existingCardNames) {
        if (draftContent == null || draftContent.isBlank()) return List.of();

        Set<String> existing = new HashSet<>(existingCardNames != null ? existingCardNames : List.of());
        List<String> sentences = splitSentences(draftContent);

        Map<String, Integer> worldCandidates = extractWorldCandidates(draftContent);

        List<Map<String, Object>> proposals = new ArrayList<>();
        proposals.addAll(buildCardProposals(worldCandidates, "World", existing, sentences, 2));
        // 按产品需求：不建议新增角色卡（Character 类型过滤掉）
        return proposals;
    }

    private List<String> splitSentences(String text) {
        String[] parts = text.split("[。！？\n]");
        List<String> result = new ArrayList<>();
        for (String p : parts) {
            String t = p.strip();
            if (!t.isEmpty()) result.add(t);
        }
        return result;
    }

    private Map<String, Integer> extractWorldCandidates(String text) {
        String suffixes = "帮|派|门|宗|城|山|谷|镇|村|府|馆|寺|庙|观|宫|殿|岛|关|寨|营|会|国|州|郡|湾|湖|河";
        Pattern pattern = Pattern.compile("([\u4e00-\u9fff]{2,8}(?:" + suffixes + "))");
        Matcher m = pattern.matcher(text);
        Map<String, Integer> counts = new LinkedHashMap<>();
        while (m.find()) {
            String name = m.group(1);
            counts.merge(name, 1, Integer::sum);
        }
        return counts;
    }

    private List<Map<String, Object>> buildCardProposals(Map<String, Integer> candidates, String cardType,
                                                          Set<String> existing, List<String> sentences, int minCount) {
        List<Map<String, Object>> proposals = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : candidates.entrySet()) {
            String name = entry.getKey();
            int count = entry.getValue();
            if (name.isBlank() || existing.contains(name) || count < minCount) continue;

            String sourceSentence = sentences.stream().filter(s -> s.contains(name)).findFirst().orElse("");
            if (sourceSentence.isBlank()) continue;

            double confidence = Math.min(0.9, 0.5 + 0.1 * Math.min(count, 4));
            Map<String, Object> proposal = new LinkedHashMap<>();
            proposal.put("name", name);
            proposal.put("type", cardType);
            proposal.put("description", sourceSentence);
            proposal.put("rationale", "在本章中多次出现（" + count + " 次），具备可复用设定价值。");
            proposal.put("source_text", sourceSentence);
            proposal.put("confidence", confidence);
            proposals.add(proposal);
        }
        return proposals;
    }

    private SceneBrief buildFallbackSceneBrief(String chapter, String title, String goal, List<String> characterNames) {        SceneBrief brief = new SceneBrief();
        brief.setChapter(chapter);
        brief.setTitle(title != null ? title : chapter);
        brief.setGoal(goal != null ? goal : "");
        brief.setCharacters(new ArrayList<>());
        brief.setWorldConstraints(new ArrayList<>());
        brief.setFacts(new ArrayList<>());
        brief.setForbidden(new ArrayList<>());
        brief.setTimelineContext(new HashMap<>());
        return brief;
    }

    /**
     * 生成章节摘要（对齐 Python SummaryMixin.generate_chapter_summary）
     */
    public ChapterSummary generateChapterSummary(String projectId, String chapter,
                                                   String chapterTitle, String finalDraft) {
        if (finalDraft == null || finalDraft.isBlank()) {
            return buildFallbackChapterSummary(chapter, chapterTitle, finalDraft);
        }

        String systemPrompt = """
                你是一位专业的章节摘要生成器。
                根据提供的章节正文，生成结构化的章节摘要。
                
                【P0-必须】严格输出 JSON，不添加任何额外文字：
                {
                  "title": "章节标题",
                  "brief_summary": "100字以内的章节核心内容摘要",
                  "key_events": ["关键事件1", "关键事件2"],
                  "new_facts": ["本章新增的重要事实1"],
                  "character_state_changes": ["角色状态变化描述"],
                  "open_loops": ["未解决的悬念或伏笔"]
                }
                """;

        String userPrompt = String.format("""
                章节ID: %s
                章节标题: %s
                
                章节正文（节选）：
                %s
                
                请生成章节摘要（严格输出 JSON）：
                """,
                chapter, chapterTitle != null ? chapterTitle : chapter,
                finalDraft.length() > 3000 ? finalDraft.substring(0, 3000) + "..." : finalDraft
        );

        try {
            List<Map<String, String>> messages = buildMessages(systemPrompt, userPrompt, List.of());
            LlmResponse response = callLlm(messages);
            return parseChapterSummaryFromJson(response.getContent(), chapter, chapterTitle, finalDraft);
        } catch (Exception e) {
            log.warn("generateChapterSummary LLM 调用失败，使用回退: {}", e.getMessage());
            return buildFallbackChapterSummary(chapter, chapterTitle, finalDraft);
        }
    }

    private ChapterSummary parseChapterSummaryFromJson(String content, String chapter,
                                                         String chapterTitle, String finalDraft) {
        ChapterSummary summary = buildFallbackChapterSummary(chapter, chapterTitle, finalDraft);
        if (content == null || content.isBlank()) return summary;

        String json = extractJson(content);
        if (json == null) return summary;

        try {
            JsonNode root = objectMapper.readTree(json);
            if (root.has("title") && !root.get("title").isNull()) {
                String t = root.get("title").asText("").trim();
                if (!t.isBlank()) summary.setTitle(t);
            }
            if (root.has("brief_summary") && !root.get("brief_summary").isNull()) {
                summary.setBriefSummary(root.get("brief_summary").asText("").trim());
            }
            if (root.has("key_events") && root.get("key_events").isArray()) {
                List<String> events = new ArrayList<>();
                for (JsonNode n : root.get("key_events")) {
                    String s = n.asText("").trim();
                    if (!s.isBlank()) events.add(s);
                }
                summary.setKeyEvents(events);
            }
            if (root.has("character_state_changes") && root.get("character_state_changes").isArray()) {
                List<String> changes = new ArrayList<>();
                for (JsonNode n : root.get("character_state_changes")) {
                    String s = n.asText("").trim();
                    if (!s.isBlank()) changes.add(s);
                }
                summary.setCharacterStateChanges(changes);
            }
            if (root.has("open_loops") && root.get("open_loops").isArray()) {
                List<String> loops = new ArrayList<>();
                for (JsonNode n : root.get("open_loops")) {
                    String s = n.asText("").trim();
                    if (!s.isBlank()) loops.add(s);
                }
                summary.setOpenLoops(loops);
            }
        } catch (Exception e) {
            log.warn("parseChapterSummaryFromJson 解析失败: {}", e.getMessage());
        }
        return summary;
    }

    private ChapterSummary buildFallbackChapterSummary(String chapter, String chapterTitle, String content) {
        String brief = "";
        if (content != null && !content.isBlank()) {
            brief = content.length() > 100 ? content.substring(0, 100) + "..." : content;
        }
        return ChapterSummary.builder()
                .chapter(chapter)
                .title(chapterTitle != null && !chapterTitle.isBlank() ? chapterTitle : chapter)
                .briefSummary(brief)
                .wordCount(content != null ? content.length() : 0)
                .keyEvents(new ArrayList<>())
                .newFacts(new ArrayList<>())
                .characterStateChanges(new ArrayList<>())
                .openLoops(new ArrayList<>())
                .build();
    }

    /**
     * 生成分卷摘要（对齐 Python SummaryMixin.generate_volume_summary）
     * 汇总该卷所有章节摘要，生成分卷级别的摘要
     */
    public Map<String, Object> generateVolumeSummary(String projectId, String volumeId,
                                                       List<ChapterSummary> chapterSummaries) {
        Map<String, Object> fallback = buildFallbackVolumeSummary(volumeId, chapterSummaries);
        if (chapterSummaries == null || chapterSummaries.isEmpty()) {
            return fallback;
        }

        StringBuilder chaptersText = new StringBuilder();
        for (ChapterSummary cs : chapterSummaries) {
            chaptersText.append("- ").append(cs.getChapter());
            if (cs.getTitle() != null && !cs.getTitle().isBlank()) {
                chaptersText.append("《").append(cs.getTitle()).append("》");
            }
            if (cs.getBriefSummary() != null && !cs.getBriefSummary().isBlank()) {
                chaptersText.append("：").append(cs.getBriefSummary());
            }
            chaptersText.append("\n");
        }

        String systemPrompt = """
                你是一位专业的分卷摘要生成器。
                根据提供的章节摘要列表，生成该卷的整体摘要。
                
                【P0-必须】严格输出 JSON，不添加任何额外文字：
                {
                  "volume_id": "分卷ID",
                  "title": "分卷标题（如无则留空）",
                  "brief_summary": "200字以内的分卷核心内容摘要",
                  "chapter_count": 0,
                  "total_word_count": 0,
                  "key_arcs": ["主要情节线1", "主要情节线2"],
                  "open_loops": ["未解决的悬念或伏笔"]
                }
                """;

        String userPrompt = String.format("""
                分卷ID: %s
                包含章节数: %d
                
                各章节摘要：
                %s
                
                请生成分卷摘要（严格输出 JSON）：
                """, volumeId, chapterSummaries.size(), chaptersText);

        try {
            List<Map<String, String>> messages = buildMessages(systemPrompt, userPrompt, List.of());
            LlmResponse response = callLlm(messages);
            return parseVolumeSummaryFromJson(response.getContent(), volumeId, chapterSummaries);
        } catch (Exception e) {
            log.warn("generateVolumeSummary LLM 调用失败，使用回退: {}", e.getMessage());
            return fallback;
        }
    }

    private Map<String, Object> parseVolumeSummaryFromJson(String content, String volumeId,
                                                             List<ChapterSummary> chapterSummaries) {
        Map<String, Object> result = buildFallbackVolumeSummary(volumeId, chapterSummaries);
        if (content == null || content.isBlank()) return result;

        String json = extractJson(content);
        if (json == null) return result;

        try {
            JsonNode root = objectMapper.readTree(json);
            if (root.has("title") && !root.get("title").isNull()) {
                String t = root.get("title").asText("").trim();
                if (!t.isBlank()) result.put("title", t);
            }
            if (root.has("brief_summary") && !root.get("brief_summary").isNull()) {
                result.put("brief_summary", root.get("brief_summary").asText("").trim());
            }
            if (root.has("key_arcs") && root.get("key_arcs").isArray()) {
                List<String> arcs = new ArrayList<>();
                for (JsonNode n : root.get("key_arcs")) {
                    String s = n.asText("").trim();
                    if (!s.isBlank()) arcs.add(s);
                }
                result.put("key_arcs", arcs);
            }
            if (root.has("open_loops") && root.get("open_loops").isArray()) {
                List<String> loops = new ArrayList<>();
                for (JsonNode n : root.get("open_loops")) {
                    String s = n.asText("").trim();
                    if (!s.isBlank()) loops.add(s);
                }
                result.put("open_loops", loops);
            }
        } catch (Exception e) {
            log.warn("parseVolumeSummaryFromJson 解析失败: {}", e.getMessage());
        }
        return result;
    }

    private Map<String, Object> buildFallbackVolumeSummary(String volumeId, List<ChapterSummary> chapterSummaries) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("volume_id", volumeId);
        summary.put("title", "");
        summary.put("brief_summary", "");
        summary.put("chapter_count", chapterSummaries != null ? chapterSummaries.size() : 0);
        int totalWords = chapterSummaries != null
                ? chapterSummaries.stream().mapToInt(cs -> cs.getWordCount() > 0 ? cs.getWordCount() : 0).sum()
                : 0;
        summary.put("total_word_count", totalWords);
        summary.put("key_arcs", new ArrayList<>());
        summary.put("open_loops", new ArrayList<>());
        return summary;
    }

    /**
     * 从最终草稿提取事实表更新（对齐 Python SummaryMixin.extract_canon_updates）
     */
    public Map<String, Object> extractCanonUpdates(String projectId, String chapter, String finalDraft) {
        if (finalDraft == null || finalDraft.isBlank()) {
            return Map.of("facts", List.of(), "timeline_events", List.of(), "character_states", List.of());
        }

        String systemPrompt = """
                你是一位专业的事实表提取器。
                从章节正文中提取新增的、对后续章节有约束力的事实。
                
                【P0-必须】严格输出 JSON，不添加任何额外文字：
                {
                  "facts": [
                    {
                      "statement": "事实陈述（一句话，简洁明确）",
                      "confidence": 0.9
                    }
                  ],
                  "timeline_events": [
                    {
                      "time": "时间描述",
                      "event": "事件描述",
                      "location": "地点",
                      "participants": ["参与者1"]
                    }
                  ],
                  "character_states": [
                    {
                      "character": "角色名",
                      "state": "状态描述",
                      "location": "所在地点"
                    }
                  ]
                }
                
                提取原则：
                - 只提取对后续章节有约束力的信息（规则/禁忌/代价/关键决定/状态变化）
                - 禁止提取琐碎重复信息
                - facts 最多5条，优先提取高价值事实
                """;

        String userPrompt = String.format("""
                章节ID: %s
                
                章节正文（节选）：
                %s
                
                请提取事实表更新（严格输出 JSON）：
                """,
                chapter,
                finalDraft.length() > 4000 ? finalDraft.substring(0, 4000) + "..." : finalDraft
        );

        try {
            List<Map<String, String>> messages = buildMessages(systemPrompt, userPrompt, List.of());
            LlmResponse response = callLlm(messages);
            return parseCanonUpdatesFromJson(response.getContent(), chapter);
        } catch (Exception e) {
            log.warn("extractCanonUpdates LLM 调用失败: {}", e.getMessage());
            return Map.of("facts", List.of(), "timeline_events", List.of(), "character_states", List.of());
        }
    }

    private Map<String, Object> parseCanonUpdatesFromJson(String content, String chapter) {
        List<Map<String, Object>> facts = new ArrayList<>();
        List<Map<String, Object>> timelineEvents = new ArrayList<>();
        List<Map<String, Object>> characterStates = new ArrayList<>();

        if (content == null || content.isBlank()) {
            return Map.of("facts", facts, "timeline_events", timelineEvents, "character_states", characterStates);
        }

        String json = extractJson(content);
        if (json == null) {
            return Map.of("facts", facts, "timeline_events", timelineEvents, "character_states", characterStates);
        }

        try {
            JsonNode root = objectMapper.readTree(json);

            if (root.has("facts") && root.get("facts").isArray()) {
                for (JsonNode n : root.get("facts")) {
                    String stmt = n.path("statement").asText("").trim();
                    if (stmt.isBlank()) continue;
                    Map<String, Object> fact = new HashMap<>();
                    fact.put("statement", stmt);
                    fact.put("confidence", n.path("confidence").asDouble(0.9));
                    fact.put("introduced_in", chapter);
                    fact.put("source", chapter);
                    facts.add(fact);
                }
            }

            if (root.has("timeline_events") && root.get("timeline_events").isArray()) {
                for (JsonNode n : root.get("timeline_events")) {
                    String event = n.path("event").asText("").trim();
                    if (event.isBlank()) continue;
                    Map<String, Object> te = new HashMap<>();
                    te.put("time", n.path("time").asText(""));
                    te.put("event", event);
                    te.put("location", n.path("location").asText(""));
                    te.put("source", chapter);
                    List<String> participants = new ArrayList<>();
                    if (n.has("participants") && n.get("participants").isArray()) {
                        for (JsonNode p : n.get("participants")) participants.add(p.asText(""));
                    }
                    te.put("participants", participants);
                    timelineEvents.add(te);
                }
            }

            if (root.has("character_states") && root.get("character_states").isArray()) {
                for (JsonNode n : root.get("character_states")) {
                    String character = n.path("character").asText("").trim();
                    if (character.isBlank()) continue;
                    Map<String, Object> cs = new HashMap<>();
                    cs.put("character", character);
                    cs.put("state", n.path("state").asText(""));
                    cs.put("location", n.path("location").asText(""));
                    cs.put("last_seen", chapter);
                    characterStates.add(cs);
                }
            }
        } catch (Exception e) {
            log.warn("parseCanonUpdatesFromJson 解析失败: {}", e.getMessage());
        }

        return Map.of("facts", facts, "timeline_events", timelineEvents, "character_states", characterStates);
    }
}
