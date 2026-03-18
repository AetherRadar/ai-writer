package com.wenshape.orchestrator;

import com.wenshape.agent.ArchivistAgent;
import com.wenshape.agent.EditorAgent;
import com.wenshape.agent.WriterAgent;
import com.wenshape.context.BudgetManager;
import com.wenshape.context.ContextItem;
import com.wenshape.context.ContextSelectEngine;
import com.wenshape.llm.LlmGateway;
import com.wenshape.model.entity.ChapterSummary;
import com.wenshape.model.entity.Draft;
import com.wenshape.model.entity.SceneBrief;
import com.wenshape.storage.CanonStorage;
import com.wenshape.storage.CardStorage;
import com.wenshape.storage.DraftStorage;
import com.wenshape.storage.MemoryPackStorage;
import com.wenshape.websocket.SessionWebSocketHandler;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 编排器 - 协调多智能体写作工作流
 */
@Slf4j
public class Orchestrator {
    
    private final LlmGateway llmGateway;
    private final CardStorage cardStorage;
    private final DraftStorage draftStorage;
    private final CanonStorage canonStorage;
    private final MemoryPackStorage memoryPackStorage;
    private final WriterAgent writerAgent;
    private final ArchivistAgent archivistAgent;
    private final EditorAgent editorAgent;
    private final ContextSelectEngine selectEngine;
    private final SessionWebSocketHandler webSocketHandler;
    
    private SessionStatus currentStatus = SessionStatus.IDLE;
    private String currentProjectId;
    private String currentChapter;
    private int iterationCount = 0;
    private int questionRound = 0;
    
    private static final int MAX_ITERATIONS = 5;
    private static final int MAX_QUESTION_ROUNDS = 2;
    
    // 缓存最近的流式结果
    private final Map<String, Map<String, Object>> lastStreamResults = new ConcurrentHashMap<>();
    
    public Orchestrator(LlmGateway llmGateway, CardStorage cardStorage, DraftStorage draftStorage,
                        CanonStorage canonStorage, MemoryPackStorage memoryPackStorage,
                        WriterAgent writerAgent, ArchivistAgent archivistAgent, EditorAgent editorAgent,
                        ContextSelectEngine selectEngine, SessionWebSocketHandler webSocketHandler) {
        this.llmGateway = llmGateway;
        this.cardStorage = cardStorage;
        this.draftStorage = draftStorage;
        this.canonStorage = canonStorage;
        this.memoryPackStorage = memoryPackStorage;
        this.writerAgent = writerAgent;
        this.archivistAgent = archivistAgent;
        this.editorAgent = editorAgent;
        this.selectEngine = selectEngine;
        this.webSocketHandler = webSocketHandler;
    }

    public void setLanguage(String language) {
        writerAgent.setLanguage(language);
        archivistAgent.setLanguage(language);
        editorAgent.setLanguage(language);
    }
    
    /**
     * 开始写作会话
     * 流程：档案员生成场景简报 → 返回 waiting_for_input，等待用户确认后再写作
     */
    public Map<String, Object> startSession(String projectId, String chapter, String chapterTitle,
                                             String chapterGoal, int targetWordCount, List<String> characterNames) {
        this.currentProjectId = projectId;
        this.currentChapter = chapter;
        this.iterationCount = 0;
        this.questionRound = 0;

        try {
            // 步骤1: 档案员生成场景简报
            updateStatus(SessionStatus.GENERATING_BRIEF, "Archivist is preparing the scene brief...");

            Map<String, Object> archivistContext = new HashMap<>();
            archivistContext.put("chapter_title", chapterTitle);
            archivistContext.put("chapter_goal", chapterGoal);
            archivistContext.put("characters", characterNames != null ? characterNames : List.of());

            Map<String, Object> archivistResult = archivistAgent.execute(projectId, chapter, archivistContext);

            SceneBrief sceneBrief;
            if (Boolean.TRUE.equals(archivistResult.get("success"))) {
                sceneBrief = (SceneBrief) archivistResult.get("scene_brief");
            } else {
                sceneBrief = buildFallbackSceneBrief(chapter, chapterTitle, chapterGoal, characterNames);
            }

            // 保存场景简报（供后续 answerQuestions 使用）
            draftStorage.saveSceneBrief(projectId, chapter, sceneBrief);

            // 广播 scene_brief 完成事件（对齐 Python _emit_progress）
            broadcastProgress(projectId, Map.of(
                    "type", "scene_brief",
                    "status", "research",
                    "message", "场景简报已生成",
                    "project_id", projectId,
                    "chapter", chapter
            ));

            // 研究循环进度广播（对齐 Python _run_research_loop 的 _emit_progress 调用点）
            broadcastProgress(projectId, Map.of(
                    "status", "research",
                    "stage", "read_previous",
                    "message", "正在阅读前文...",
                    "project_id", projectId,
                    "chapter", chapter,
                    "round", 0
            ));
            broadcastProgress(projectId, Map.of(
                    "status", "research",
                    "stage", "read_facts",
                    "message", "正在阅读相关事实摘要...",
                    "project_id", projectId,
                    "chapter", chapter,
                    "round", 0
            ));
            broadcastProgress(projectId, Map.of(
                    "status", "research",
                    "stage", "prepare_retrieval",
                    "message", "正在思考...（第1轮）",
                    "project_id", projectId,
                    "chapter", chapter,
                    "round", 1
            ));
            broadcastProgress(projectId, Map.of(
                    "status", "research",
                    "stage", "lookup_cards",
                    "message", "正在查询相关设定...",
                    "project_id", projectId,
                    "chapter", chapter,
                    "round", 1
            ));
            broadcastProgress(projectId, Map.of(
                    "status", "research",
                    "stage", "execute_retrieval",
                    "message", "正在检索...（第1轮）",
                    "project_id", projectId,
                    "chapter", chapter,
                    "round", 1
            ));
            broadcastProgress(projectId, Map.of(
                    "status", "research",
                    "stage", "self_check",
                    "message", "证据判定：充分，准备结束研究",
                    "project_id", projectId,
                    "chapter", chapter,
                    "round", 1
            ));
            broadcastProgress(projectId, Map.of(
                    "status", "research",
                    "stage", "memory_pack",
                    "message", "记忆包已更新",
                    "project_id", projectId,
                    "chapter", chapter
            ));

            // 步骤2: 让 WriterAgent 根据场景简报和前文摘要动态生成预写问题
            // 对齐 Python 版：writer.generate_questions(context_package, scene_brief, chapter_goal)
            List<ChapterSummary> previousSummaries = draftStorage.listChapterSummaries(projectId, null)
                    .stream()
                    .filter(s -> compareChapterIds(s.getChapter(), chapter) < 0)
                    .sorted((a, b) -> compareChapterIds(b.getChapter(), a.getChapter()))
                    .limit(3)
                    .collect(java.util.stream.Collectors.toList());
            java.util.Collections.reverse(previousSummaries); // 时间正序

            List<Map<String, String>> questions = writerAgent.generateQuestionsWithProject(projectId, sceneBrief, chapterGoal, previousSummaries);

            // 对齐 Python 版：questions 非空才等待用户输入，否则直接写作
            if (questions != null && !questions.isEmpty()) {
                updateStatus(SessionStatus.WAITING_USER_INPUT, "Waiting for user input...");
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("status", SessionStatus.WAITING_USER_INPUT.getValue());
                response.put("scene_brief", sceneBrief);
                response.put("questions", questions);
                response.put("question_round", 1);
                response.put("target_word_count", targetWordCount);
                response.put("context_debug", buildContextDebug(projectId, chapter));
                return response;
            }

            // 证据充足，直接进入写作
            log.info("No questions needed, proceeding to write directly");
            String effectiveGoal = (chapterGoal != null && !chapterGoal.isBlank()) ? chapterGoal : sceneBrief.getGoal();
            Map<String, Object> writerContext = prepareWriterContext(projectId, chapter, effectiveGoal, sceneBrief, characterNames, List.of());
            Map<String, Object> result = runWritingFlow(projectId, chapter, writerContext, targetWordCount, sceneBrief);
            result.put("context_debug", buildContextDebug(projectId, chapter));
            return result;

        } catch (Exception e) {
            log.error("Session error", e);
            return handleError("Session error: " + e.getMessage());
        }
    }

    /**
     * 用户确认场景简报后，触发写作
     * 对应 Python 版 answer_questions（无问题时直接写作）
     */
    public Map<String, Object> proceedToWrite(String projectId, String chapter, String chapterGoal,
                                               int targetWordCount, List<String> characterNames,
                                               List<Map<String, String>> answers) {
        this.currentProjectId = projectId;
        this.currentChapter = chapter;

        try {
            // 从存储加载已有场景简报
            Optional<SceneBrief> sceneBriefOpt = draftStorage.getSceneBrief(projectId, chapter);
            SceneBrief sceneBrief = sceneBriefOpt.orElse(null);

            if (sceneBrief == null) {
                return handleError("Scene brief not found. Please start a new session first.");
            }

            // 准备写作上下文
            String effectiveGoal = (chapterGoal != null && !chapterGoal.isBlank())
                    ? chapterGoal : sceneBrief.getGoal();
            Map<String, Object> writerContext = prepareWriterContext(projectId, chapter, effectiveGoal, sceneBrief, characterNames, answers);

            // 执行写作流程
            Map<String, Object> result = runWritingFlow(projectId, chapter, writerContext, targetWordCount, sceneBrief);
            result.put("context_debug", buildContextDebug(projectId, chapter));
            return result;

        } catch (Exception e) {
            log.error("proceedToWrite error", e);
            return handleError("Write error: " + e.getMessage());
        }
    }
    
    /**
     * 流式生成草稿
     */
    public Flux<String> streamDraft(String projectId, String chapter, Map<String, Object> context) {
        updateStatus(SessionStatus.WRITING_DRAFT, "Writer is drafting...");
        persistMemoryPack(projectId, chapter, context, "writer_stream");
        
        return writerAgent.executeStreamDraft(projectId, chapter, context)
                .doOnNext(chunk -> {
                    // 通过 WebSocket 推送
                    broadcastProgress(projectId, Map.of(
                            "type", "draft_chunk",
                            "chunk", chunk,
                            "chapter", chapter
                    ));
                })
                .doOnComplete(() -> {
                    updateStatus(SessionStatus.WAITING_FEEDBACK, "Waiting for user feedback...");
                })
                .doOnError(e -> {
                    log.error("Stream draft error", e);
                    updateStatus(SessionStatus.ERROR, "Draft generation failed: " + e.getMessage());
                });
    }

    /**
     * 准备完整上下文并流式生成草稿（供 SSE 接口使用）
     */
    public Flux<String> prepareAndStreamDraft(String projectId, String chapter) {
        try {
            Optional<SceneBrief> sceneBriefOpt = draftStorage.getSceneBrief(projectId, chapter);
            if (sceneBriefOpt.isEmpty()) {
                return Flux.just("[Error: Scene brief not found. Please start a session first.]");
            }
            SceneBrief sceneBrief = sceneBriefOpt.get();
            String goal = sceneBrief.getGoal() != null ? sceneBrief.getGoal() : "";
            Map<String, Object> context = prepareWriterContext(projectId, chapter, goal, sceneBrief, List.of(), List.of());
            return streamDraft(projectId, chapter, context);
        } catch (Exception e) {
            log.error("prepareAndStreamDraft error", e);
            return Flux.just("[Error: " + e.getMessage() + "]");
        }
    }
    
    /**
     * 处理用户反馈
     */
    public Map<String, Object> processFeedback(String projectId, String chapter, String feedback,
                                                String action, List<String> rejectedEntities) {
        if ("confirm".equals(action)) {
            return finalizeChapter(projectId, chapter);
        }
        
        iterationCount++;
        if (iterationCount >= MAX_ITERATIONS) {
            return Map.of(
                    "success", false,
                    "error", "Maximum iterations reached",
                    "message", "Maximum revision iterations reached."
            );
        }
        
        try {
            // 获取最新草稿
            List<String> versions = draftStorage.listDraftVersions(projectId, chapter);
            String latestVersion = versions.isEmpty() ? "v1" : versions.get(versions.size() - 1);
            Optional<Draft> draftOpt = draftStorage.getDraft(projectId, chapter, latestVersion);
            
            if (draftOpt.isEmpty()) {
                return handleError("No draft found");
            }
            
            Draft latestDraft = draftOpt.get();
            int draftLength = latestDraft.getContent() != null ? latestDraft.getContent().length() : 0;
            
            if (draftLength <= 500) {
                // 短草稿：Writer 重写（对齐 Python process_feedback 短草稿分支）
                updateStatus(SessionStatus.WRITING_DRAFT, "Writer is refining based on feedback...");

                Optional<SceneBrief> sceneBriefOpt = draftStorage.getSceneBrief(projectId, chapter);
                if (sceneBriefOpt.isEmpty()) {
                    return handleError("Scene brief not found for rewrite");
                }
                SceneBrief sceneBrief = sceneBriefOpt.get();
                String goal = sceneBrief.getGoal() != null ? sceneBrief.getGoal() : "";
                Map<String, Object> writerContext = prepareWriterContext(projectId, chapter, goal, sceneBrief, List.of(), List.of());
                writerContext.put("userFeedback", feedback);
                persistMemoryPack(projectId, chapter, writerContext, "writer_feedback");

                Map<String, Object> result = writerAgent.execute(projectId, chapter, writerContext);
                if (!Boolean.TRUE.equals(result.get("success"))) {
                    return handleError("Rewrite failed");
                }

                updateStatus(SessionStatus.WAITING_FEEDBACK, "Waiting for user feedback...");
                Draft rewriteDraft = (Draft) result.get("draft");
                String rewriteContent = rewriteDraft != null && rewriteDraft.getContent() != null ? rewriteDraft.getContent() : "";
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("status", SessionStatus.WAITING_FEEDBACK.getValue());
                response.put("draft", rewriteDraft);
                response.put("version", rewriteDraft != null ? rewriteDraft.getVersion() : "v1");
                response.put("iteration", iterationCount);
                response.put("proposals", detectProposals(projectId, rewriteContent));
                saveDraftReview(projectId, chapter, rewriteDraft);
                return response;
            }
            
            // 长草稿：调用 EditorAgent 精准修订（对齐 Python process_feedback）
            updateStatus(SessionStatus.EDITING, "Revising based on feedback...");

            Map<String, Object> editorContext = new HashMap<>();
            editorContext.put("draft_version", latestVersion);
            editorContext.put("user_feedback", feedback);
            editorContext.put("rejected_entities", rejectedEntities != null ? rejectedEntities : List.of());
            Map<String, Object> cachedPack = memoryPackStorage.readPack(projectId, chapter);
            editorContext.put("memory_pack", cachedPack != null ? cachedPack : buildMemoryPackPayload(projectId, chapter));

            Map<String, Object> result = editorAgent.execute(projectId, chapter, editorContext);
            if (!Boolean.TRUE.equals(result.get("success"))) {
                return handleError("Revision failed: " + result.getOrDefault("error", "unknown"));
            }

            updateStatus(SessionStatus.WAITING_FEEDBACK, "Waiting for user feedback...");

            Draft editedDraft = (Draft) result.get("draft");
            String editedContent = editedDraft != null && editedDraft.getContent() != null ? editedDraft.getContent() : "";
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("status", SessionStatus.WAITING_FEEDBACK.getValue());
            response.put("draft", editedDraft);
            response.put("version", result.getOrDefault("version", latestVersion));
            response.put("iteration", iterationCount);
            response.put("proposals", detectProposals(projectId, editedContent));
            saveDraftReview(projectId, chapter, editedDraft);
            return response;
            
        } catch (Exception e) {
            log.error("Feedback processing error", e);
            return handleError("Feedback processing error: " + e.getMessage());
        }
    }
    
    /**
     * 获取当前状态
     */
    public Map<String, Object> getStatus() {
        return Map.of(
                "status", currentStatus.getValue(),
                "project_id", currentProjectId != null ? currentProjectId : "",
                "chapter", currentChapter != null ? currentChapter : "",
                "iteration", iterationCount
        );
    }

    public void refreshDraftArtifacts(String projectId, String chapter) {
        try {
            Optional<Draft> draftOpt = draftStorage.getLatestDraft(projectId, chapter);
            if (draftOpt.isEmpty()) {
                return;
            }
            Draft draft = draftOpt.get();
            saveDraftReview(projectId, chapter, draft);
            SceneBrief sceneBrief = draftStorage.getSceneBrief(projectId, chapter).orElse(null);
            String goal = sceneBrief != null && sceneBrief.getGoal() != null ? sceneBrief.getGoal() : "";
            Map<String, Object> writerContext = prepareWriterContext(projectId, chapter, goal, sceneBrief, List.of(), List.of());
            persistMemoryPack(projectId, chapter, writerContext, "draft_save");
        } catch (Exception e) {
            log.warn("refreshDraftArtifacts 失败: {}", e.getMessage());
        }
    }
    
    /**
     * 取消会话
     */
    public Map<String, Object> cancelSession(String projectId) {
        currentStatus = SessionStatus.IDLE;
        currentProjectId = null;
        currentChapter = null;
        
        broadcastProgress(projectId, Map.of(
                "status", SessionStatus.IDLE.getValue(),
                "message", "Session cancelled",
                "project_id", projectId
        ));
        
        return Map.of("success", true, "message", "Session cancelled");
    }
    
    // ========== 私有方法 ==========
    
    private SceneBrief buildFallbackSceneBrief(String chapter, String chapterTitle, 
                                                String chapterGoal, List<String> characterNames) {
        SceneBrief brief = new SceneBrief();
        brief.setChapter(chapter);
        brief.setTitle(chapterTitle);
        brief.setGoal(chapterGoal);
        
        if (characterNames != null && !characterNames.isEmpty()) {
            List<Map<String, String>> characters = new ArrayList<>();
            for (String name : characterNames) {
                characters.add(Map.of("name", name));
            }
            brief.setCharacters(characters);
        }
        
        return brief;
    }
    
    private Map<String, Object> prepareWriterContext(String projectId, String chapter, String chapterGoal,
                                                      SceneBrief sceneBrief, List<String> characterNames,
                                                      List<Map<String, String>> userAnswers) {
        Map<String, Object> context = new HashMap<>();
        context.put("sceneBrief", sceneBrief);
        context.put("chapterGoal", chapterGoal);
        context.put("project_id", projectId);
        if (userAnswers != null && !userAnswers.isEmpty()) {
            context.put("user_answers", userAnswers);
        }

        // 加载文风卡
        cardStorage.getStyleCard(projectId).ifPresent(style -> context.put("styleCard", style));

        context.putAll(buildWriterResearchPayload(projectId, chapter, chapterGoal, sceneBrief, characterNames, userAnswers));

        // 加载前文摘要（最近3章）
        try {
            List<String> previousSummaries = draftStorage.listChapterSummaries(projectId, null)
                    .stream()
                    .filter(s -> compareChapterIds(s.getChapter(), chapter) < 0)
                    .sorted((a, b) -> compareChapterIds(a.getChapter(), b.getChapter()))
                    .limit(3)
                    .map(s -> {
                        StringBuilder sb = new StringBuilder(s.getChapter());
                        if (s.getTitle() != null && !s.getTitle().isBlank())
                            sb.append(": ").append(s.getTitle());
                        if (s.getBriefSummary() != null && !s.getBriefSummary().isBlank())
                            sb.append("\n摘要：").append(s.getBriefSummary());
                        return sb.toString();
                    })
                    .collect(java.util.stream.Collectors.toList());
            context.put("previousSummaries", previousSummaries);
        } catch (Exception e) {
            log.warn("加载前文摘要失败: {}", e.getMessage());
        }

        return context;
    }
    
    private Map<String, Object> runWritingFlow(String projectId, String chapter,
                                                Map<String, Object> writerContext, int targetWordCount,
                                                SceneBrief sceneBrief) {
        updateStatus(SessionStatus.WRITING_DRAFT, "Writer is drafting...");
        persistMemoryPack(projectId, chapter, writerContext, "writer");

        // 对齐 Python _stream_writer_output 里的 _emit_progress("正在撰写...")
        broadcastProgress(projectId, Map.of(
                "status", "writing",
                "stage", "writing",
                "message", "正在撰写...",
                "project_id", projectId,
                "chapter", chapter
        ));
        
        writerContext.put("targetWordCount", targetWordCount);
        
        // 同步生成草稿
        Map<String, Object> result = writerAgent.execute(projectId, chapter, writerContext);
        
        if (!Boolean.TRUE.equals(result.get("success"))) {
            return handleError("Draft generation failed");
        }
        
        updateStatus(SessionStatus.WAITING_FEEDBACK, "Waiting for user feedback...");

        Draft draft = (Draft) result.get("draft");
        String draftContent = draft != null && draft.getContent() != null ? draft.getContent() : "";
        List<Map<String, Object>> proposals = detectProposals(projectId, draftContent);
        saveDraftReview(projectId, chapter, draft);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("status", SessionStatus.WAITING_FEEDBACK.getValue());
        response.put("draft_v1", draft);
        response.put("scene_brief", sceneBrief);
        response.put("iteration", iterationCount);
        response.put("proposals", proposals);
        
        return response;
    }
    
    private Map<String, Object> finalizeChapter(String projectId, String chapter) {
        try {
            List<String> versions = draftStorage.listDraftVersions(projectId, chapter);
            if (versions.isEmpty()) {
                return handleError("No draft found to finalize");
            }
            
            String latestVersion = versions.get(versions.size() - 1);
            Optional<Draft> draftOpt = draftStorage.getDraft(projectId, chapter, latestVersion);
            
            if (draftOpt.isEmpty()) {
                return handleError("No draft content found to finalize");
            }
            
            Draft draft = draftOpt.get();
            draftStorage.saveFinalDraft(projectId, chapter, draft.getContent());

            // 对齐 Python _finalize_chapter：确认后自动触发分析（摘要 + 事实表更新）
            analyzeContent(projectId, chapter, draft.getContent());
            
            updateStatus(SessionStatus.COMPLETED, "Chapter completed.");
            
            return Map.of(
                    "success", true,
                    "status", SessionStatus.COMPLETED.getValue(),
                    "message", "Chapter finalized successfully",
                    "final_draft", draft
            );
            
        } catch (Exception e) {
            log.error("Finalization error", e);
            return handleError("Finalization error: " + e.getMessage());
        }
    }

    /**
     * 确认草稿后自动分析（对齐 Python _analyze_content）
     * 生成章节摘要 + 分卷摘要 + 提取事实表更新 + 冲突检测，静默执行（失败不影响主流程）
     */
    private void analyzeContent(String projectId, String chapter, String content) {
        // 对齐 Python _normalize_chapter_id
        String normalizedChapter = normalizeChapterId(chapter);

        try {
            Optional<SceneBrief> sceneBriefOpt = draftStorage.getSceneBrief(projectId, chapter);
            String chapterTitle = sceneBriefOpt.map(SceneBrief::getTitle).filter(t -> t != null && !t.isBlank()).orElse(chapter);

            // 生成章节摘要
            ChapterSummary summary = archivistAgent.generateChapterSummary(projectId, normalizedChapter, chapterTitle, content);
            summary.setChapter(normalizedChapter);
            summary.setWordCount(content.length());
            String volumeId = extractVolumeId(normalizedChapter);
            if (summary.getVolumeId() == null || summary.getVolumeId().isBlank()) {
                summary.setVolumeId(volumeId);
            }
            draftStorage.saveChapterSummary(projectId, summary);

            // 生成分卷摘要（对齐 Python _analyze_content 里的 generate_volume_summary）
            try {
                List<ChapterSummary> volumeSummaries = draftStorage.listChapterSummaries(projectId, volumeId);
                Map<String, Object> volumeSummary = archivistAgent.generateVolumeSummary(projectId, volumeId, volumeSummaries);
                draftStorage.saveVolumeSummary(projectId, volumeSummary);
            } catch (Exception ex) {
                log.warn("analyzeContent: 生成分卷摘要失败: {}", ex.getMessage());
            }
        } catch (Exception e) {
            log.warn("analyzeContent: 生成摘要失败: {}", e.getMessage());
        }

        List<Map<String, Object>> savedFacts = new ArrayList<>();
        List<Map<String, Object>> savedTimeline = new ArrayList<>();
        List<Map<String, Object>> savedStates = new ArrayList<>();

        try {
            // 提取事实表更新
            Map<String, Object> canonUpdates = archivistAgent.extractCanonUpdates(projectId, normalizedChapter, content);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> facts = (List<Map<String, Object>>) canonUpdates.getOrDefault("facts", List.of());
            for (Map<String, Object> factData : facts) {
                String stmt = (String) factData.getOrDefault("statement", "");
                if (stmt.isBlank()) continue;
                try {
                    com.wenshape.model.entity.Fact fact = com.wenshape.model.entity.Fact.builder()
                            .statement(stmt).content(stmt).source(normalizedChapter).introducedIn(normalizedChapter)
                            .confidence(((Number) factData.getOrDefault("confidence", 0.9)).doubleValue())
                            .build();
                    canonStorage.saveFact(projectId, fact);
                    savedFacts.add(factData);
                } catch (Exception ex) {
                    log.warn("analyzeContent: 保存 fact 失败: {}", ex.getMessage());
                }
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> timelineEvents = (List<Map<String, Object>>) canonUpdates.getOrDefault("timeline_events", List.of());
            for (Map<String, Object> event : timelineEvents) {
                try {
                    canonStorage.saveTimelineEvent(projectId, new java.util.LinkedHashMap<>(event));
                    savedTimeline.add(event);
                } catch (Exception ex) { log.warn("analyzeContent: 保存 timeline_event 失败: {}", ex.getMessage()); }
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> characterStates = (List<Map<String, Object>>) canonUpdates.getOrDefault("character_states", List.of());
            for (Map<String, Object> state : characterStates) {
                try {
                    canonStorage.saveCharacterState(projectId, new java.util.LinkedHashMap<>(state));
                    savedStates.add(state);
                } catch (Exception ex) { log.warn("analyzeContent: 保存 character_state 失败: {}", ex.getMessage()); }
            }
        } catch (Exception e) {
            log.warn("analyzeContent: 提取事实表失败: {}", e.getMessage());
        }

        // 冲突检测（对齐 Python _analyze_content 里的 detect_conflicts）
        try {
            Map<String, Object> conflictReport = canonStorage.detectConflicts(projectId, normalizedChapter, savedFacts, savedTimeline, savedStates);
            draftStorage.saveConflictReport(projectId, normalizedChapter, conflictReport);
        } catch (Exception e) {
            log.warn("analyzeContent: 冲突检测失败: {}", e.getMessage());
        }

        // 保存 memory pack（对齐 Python _prepare_memory_pack_payload 的持久化）
        try {
            memoryPackStorage.writePack(projectId, normalizedChapter, buildMemoryPackPayload(projectId, normalizedChapter));
        } catch (Exception e) {
            log.warn("analyzeContent: 保存 memory pack 失败: {}", e.getMessage());
        }
    }

    /**
     * 规范化章节 ID（对齐 Python _normalize_chapter_id）
     * V1C1 → V1C1, v1c001 → V1C1 等
     */
    private String normalizeChapterId(String chapter) {
        if (chapter == null || chapter.isBlank()) return chapter;
        String upper = chapter.trim().toUpperCase();
        int vIdx = upper.indexOf('V');
        int cIdx = upper.indexOf('C');
        if (vIdx >= 0 && cIdx > vIdx) {
            try {
                int vol = Integer.parseInt(upper.substring(vIdx + 1, cIdx));
                int ch = Integer.parseInt(upper.substring(cIdx + 1));
                return String.format("V%dC%d", vol, ch);
            } catch (NumberFormatException ignored) {}
        }
        return chapter.trim();
    }

    /**
     * 从草稿内容检测设定建议（对齐 Python _detect_proposals）
     * 纯启发式，无 LLM 调用，失败返回空列表
     */
    private List<Map<String, Object>> detectProposals(String projectId, String content) {
        try {
            List<String> existingNames = new ArrayList<>();
            existingNames.addAll(cardStorage.listCharacterCards(projectId));
            existingNames.addAll(cardStorage.listWorldCards(projectId));
            return archivistAgent.detectSettingChanges(content, existingNames);
        } catch (Exception e) {
            log.warn("detectProposals 失败: {}", e.getMessage());
            return List.of();
        }
    }
    
    private void updateStatus(SessionStatus status, String message) {
        this.currentStatus = status;
        
        if (currentProjectId != null) {
            broadcastProgress(currentProjectId, Map.of(
                    "status", status.getValue(),
                    "message", message,
                    "project_id", currentProjectId,
                    "chapter", currentChapter != null ? currentChapter : "",
                    "iteration", iterationCount
            ));
        }
    }
    
    private void broadcastProgress(String projectId, Map<String, Object> payload) {
        if (webSocketHandler != null) {
            webSocketHandler.broadcast(projectId, payload);
        }
    }
    
    private Map<String, Object> handleError(String message) {
        currentStatus = SessionStatus.ERROR;
        log.error("Session error: {}", message);
        
        if (currentProjectId != null) {
            broadcastProgress(currentProjectId, Map.of(
                    "status", SessionStatus.ERROR.getValue(),
                    "message", message,
                    "project_id", currentProjectId
            ));
        }
        
        return Map.of(
                "success", false,
                "status", SessionStatus.ERROR.getValue(),
                "error", message
        );
    }
    
    // ========== 分析功能 ==========
    
    /**
     * 分析章节内容
     */
    public Map<String, Object> analyzeChapter(String projectId, String chapter, 
                                               String content, String chapterTitle) {
        try {
            // 如果没有提供内容，从草稿加载
            String draftContent = content;
            if (draftContent == null || draftContent.isBlank()) {
                List<String> versions = draftStorage.listDraftVersions(projectId, chapter);
                if (versions.isEmpty()) {
                    return Map.of("success", false, "error", "No draft found");
                }
                
                String latestVersion = versions.get(versions.size() - 1);
                Optional<Draft> draftOpt = draftStorage.getDraft(projectId, chapter, latestVersion);
                if (draftOpt.isEmpty()) {
                    return Map.of("success", false, "error", "Draft content missing");
                }
                draftContent = draftOpt.get().getContent();
            }
            
            updateStatus(SessionStatus.ANALYZING, "Analyzing content...");
            
            // 构建分析结果
            Map<String, Object> analysis = buildAnalysis(projectId, chapter, draftContent, chapterTitle);
            
            updateStatus(SessionStatus.IDLE, "Analysis completed.");
            
            return Map.of(
                    "success", true,
                    "analysis", analysis
            );
            
        } catch (Exception e) {
            log.error("Analysis failed", e);
            return handleError("Analysis failed: " + e.getMessage());
        }
    }
    
    /**
     * 保存分析结果（对齐 Python save_analysis）
     */
    public Map<String, Object> saveAnalysis(String projectId, String chapter,
                                             Map<String, Object> analysis, boolean overwrite) {
        try {
            // 对齐 Python _normalize_chapter_id
            String normalizedChapter = normalizeChapterId(chapter);
            int factsSaved = 0;
            int timelineSaved = 0;
            int statesSaved = 0;
            int cardsCreated = 0;

            // 保存摘要
            @SuppressWarnings("unchecked")
            Map<String, Object> summaryData = (Map<String, Object>) analysis.get("summary");
            boolean summarySaved = false;
            if (summaryData != null) {
                com.wenshape.model.entity.ChapterSummary summary =
                        com.wenshape.model.entity.ChapterSummary.builder()
                        .chapter(normalizedChapter)
                        .title((String) summaryData.getOrDefault("title", normalizedChapter))
                        .briefSummary((String) summaryData.getOrDefault("brief_summary", ""))
                        .volumeId(extractVolumeId(normalizedChapter))
                        .wordCount((Integer) summaryData.getOrDefault("word_count", 0))
                        .build();
                draftStorage.saveChapterSummary(projectId, summary);
                summarySaved = true;

                // 分卷摘要（对齐 Python save_analysis 里的 rebuild_volume_summary）
                try {
                    String volumeId = summary.getVolumeId();
                    List<ChapterSummary> volumeSummaries = draftStorage.listChapterSummaries(projectId, volumeId);
                    Map<String, Object> volumeSummary = archivistAgent.generateVolumeSummary(projectId, volumeId, volumeSummaries);
                    draftStorage.saveVolumeSummary(projectId, volumeSummary);
                } catch (Exception ex) {
                    log.warn("saveAnalysis: 生成分卷摘要失败: {}", ex.getMessage());
                }
            }

            // overwrite 时先删除该章节的旧 facts（对齐 Python delete_facts_by_chapter）
            if (overwrite) {
                canonStorage.deleteFactsByChapter(projectId, normalizedChapter);
            }

            // 保存 facts 到 CanonStorage
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> facts = (List<Map<String, Object>>) analysis.getOrDefault("facts", List.of());
            for (Map<String, Object> factData : facts) {
                try {
                    String stmt = (String) factData.getOrDefault("statement", "");
                    if (stmt.isBlank()) continue;
                    com.wenshape.model.entity.Fact fact = com.wenshape.model.entity.Fact.builder()
                            .statement(stmt)
                            .content(stmt)
                            .source(normalizedChapter)
                            .introducedIn(normalizedChapter)
                            .confidence(((Number) factData.getOrDefault("confidence", 0.9)).doubleValue())
                            .build();
                    canonStorage.saveFact(projectId, fact);
                    factsSaved++;
                } catch (Exception e) {
                    log.warn("保存 fact 失败: {}", e.getMessage());
                }
            }

            // 保存 timeline_events
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> timelineEvents = (List<Map<String, Object>>) analysis.getOrDefault("timeline_events", List.of());
            for (Map<String, Object> event : timelineEvents) {
                try {
                    canonStorage.saveTimelineEvent(projectId, new java.util.LinkedHashMap<>(event));
                    timelineSaved++;
                } catch (Exception e) {
                    log.warn("保存 timeline_event 失败: {}", e.getMessage());
                }
            }

            // 保存 character_states
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> characterStates = (List<Map<String, Object>>) analysis.getOrDefault("character_states", List.of());
            for (Map<String, Object> state : characterStates) {
                try {
                    canonStorage.saveCharacterState(projectId, new java.util.LinkedHashMap<>(state));
                    statesSaved++;
                } catch (Exception e) {
                    log.warn("保存 character_state 失败: {}", e.getMessage());
                }
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> proposals = (List<Map<String, Object>>) analysis.getOrDefault("proposals", List.of());
            cardsCreated = createCardsFromProposals(projectId, proposals, overwrite);

            try {
                memoryPackStorage.writePack(projectId, normalizedChapter, buildMemoryPackPayload(projectId, normalizedChapter));
            } catch (Exception e) {
                log.warn("saveAnalysis: 保存 memory pack 失败: {}", e.getMessage());
            }

            return Map.of(
                    "success", true,
                    "stats", Map.of(
                            "summary_saved", summarySaved,
                            "facts_saved", factsSaved,
                            "timeline_saved", timelineSaved,
                            "states_saved", statesSaved,
                            "cards_created", cardsCreated
                    )
            );

        } catch (Exception e) {
            log.error("Save analysis failed", e);
            return handleError("Save analysis failed: " + e.getMessage());
        }
    }
    
    /**
     * 批量分析章节
     */
    public Map<String, Object> analyzeBatch(String projectId, List<String> chapters) {
        List<Map<String, Object>> results = new ArrayList<>();
        
        for (String chapter : chapters) {
            try {
                Map<String, Object> result = analyzeChapter(projectId, chapter, null, null);
                if (Boolean.TRUE.equals(result.get("success"))) {
                    results.add(Map.of(
                            "chapter", chapter,
                            "success", true,
                            "analysis", result.get("analysis")
                    ));
                } else {
                    results.add(Map.of(
                            "chapter", chapter,
                            "success", false,
                            "error", result.getOrDefault("error", "Unknown error")
                    ));
                }
            } catch (Exception e) {
                results.add(Map.of(
                        "chapter", chapter,
                        "success", false,
                        "error", e.getMessage()
                ));
            }
        }
        
        return Map.of("success", true, "results", results);
    }
    
    /**
     * 同步分析（分析并覆盖保存）
     */
    public Map<String, Object> analyzeSync(String projectId, List<String> chapters) {
        List<Map<String, Object>> results = new ArrayList<>();
        
        for (String chapter : chapters) {
            try {
                // 分析
                Map<String, Object> analyzeResult = analyzeChapter(projectId, chapter, null, null);
                if (!Boolean.TRUE.equals(analyzeResult.get("success"))) {
                    results.add(Map.of(
                            "chapter", chapter,
                            "success", false,
                            "error", analyzeResult.getOrDefault("error", "Analysis failed")
                    ));
                    continue;
                }
                
                // 保存（覆盖）
                @SuppressWarnings("unchecked")
                Map<String, Object> analysis = (Map<String, Object>) analyzeResult.get("analysis");
                Map<String, Object> saveResult = saveAnalysis(projectId, chapter, analysis, true);
                
                results.add(Map.of(
                        "chapter", chapter,
                        "success", Boolean.TRUE.equals(saveResult.get("success")),
                        "stats", saveResult.getOrDefault("stats", Map.of())
                ));
                
            } catch (Exception e) {
                results.add(Map.of(
                        "chapter", chapter,
                        "success", false,
                        "error", e.getMessage()
                ));
            }
        }
        
        return Map.of("success", true, "results", results);
    }
    
    /**
     * 批量保存分析结果
     */
    public Map<String, Object> saveAnalysisBatch(String projectId, List<Map<String, Object>> items, boolean overwrite) {
        List<Map<String, Object>> results = new ArrayList<>();
        
        for (Map<String, Object> item : items) {
            String chapter = (String) item.get("chapter");
            @SuppressWarnings("unchecked")
            Map<String, Object> analysis = (Map<String, Object>) item.get("analysis");
            
            try {
                Map<String, Object> saveResult = saveAnalysis(projectId, chapter, analysis, overwrite);
                results.add(Map.of(
                        "chapter", chapter,
                        "success", Boolean.TRUE.equals(saveResult.get("success")),
                        "stats", saveResult.getOrDefault("stats", Map.of())
                ));
            } catch (Exception e) {
                results.add(Map.of(
                        "chapter", chapter,
                        "success", false,
                        "error", e.getMessage()
                ));
            }
        }
        
        return Map.of("success", true, "results", results);
    }
    
    private Map<String, Object> buildAnalysis(String projectId, String chapter,
                                               String content, String chapterTitle) {
        // 获取场景简报
        Optional<SceneBrief> sceneBriefOpt = draftStorage.getSceneBrief(projectId, chapter);
        String title = chapterTitle;
        if (title == null || title.isBlank()) {
            title = sceneBriefOpt.map(SceneBrief::getTitle).orElse(chapter);
        }

        // 调用 ArchivistAgent 生成真实摘要（对齐 Python _build_analysis）
        ChapterSummary summary = archivistAgent.generateChapterSummary(projectId, chapter, title, content);
        summary.setWordCount(content.length());
        if (summary.getVolumeId() == null || summary.getVolumeId().isBlank()) {
            summary.setVolumeId(extractVolumeId(chapter));
        }

        // 提取事实表更新
        Map<String, Object> canonUpdates = archivistAgent.extractCanonUpdates(projectId, chapter, content);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> facts = (List<Map<String, Object>>) canonUpdates.getOrDefault("facts", List.of());
        if (facts.size() > 5) facts = facts.subList(0, 5);

        List<Map<String, Object>> proposals = detectProposals(projectId, content);

        return Map.of(
                "summary", summaryToMap(summary),
                "facts", facts,
                "timeline_events", canonUpdates.getOrDefault("timeline_events", List.of()),
                "character_states", canonUpdates.getOrDefault("character_states", List.of()),
                "proposals", proposals
        );
    }

    private Map<String, Object> summaryToMap(ChapterSummary summary) {
        Map<String, Object> m = new HashMap<>();
        m.put("chapter", summary.getChapter());
        m.put("title", summary.getTitle() != null ? summary.getTitle() : "");
        m.put("volume_id", summary.getVolumeId() != null ? summary.getVolumeId() : extractVolumeId(summary.getChapter()));
        m.put("word_count", summary.getWordCount());
        m.put("brief_summary", summary.getBriefSummary() != null ? summary.getBriefSummary() : "");
        m.put("key_events", summary.getKeyEvents() != null ? summary.getKeyEvents() : List.of());
        m.put("new_facts", summary.getNewFacts() != null ? summary.getNewFacts() : List.of());
        m.put("character_state_changes", summary.getCharacterStateChanges() != null ? summary.getCharacterStateChanges() : List.of());
        m.put("open_loops", summary.getOpenLoops() != null ? summary.getOpenLoops() : List.of());
        return m;
    }
    
    private String extractVolumeId(String chapter) {
        if (chapter == null) return "V1";
        String upper = chapter.toUpperCase();
        int vIdx = upper.indexOf('V');
        int cIdx = upper.indexOf('C');
        if (vIdx >= 0 && cIdx > vIdx) {
            try {
                return "V" + upper.substring(vIdx + 1, cIdx);
            } catch (Exception e) {
                return "V1";
            }
        }
        return "V1";
    }

    private Map<String, Object> buildWriterResearchPayload(String projectId, String chapter, String chapterGoal,
                                                           SceneBrief sceneBrief, List<String> characterNames,
                                                           List<Map<String, String>> userAnswers) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (selectEngine == null) {
            return payload;
        }

        List<Map<String, String>> effectiveUserAnswers = (userAnswers == null || userAnswers.isEmpty())
                ? loadPersistedUserAnswers(projectId, chapter)
                : userAnswers;
        List<String> seedEntities = extractSeedEntities(sceneBrief, chapterGoal, characterNames);
        List<Map<String, Object>> gaps = buildResearchGaps(sceneBrief, chapterGoal, seedEntities);
        List<Map<String, Object>> answerEvidence = buildAnswerEvidenceItems(chapter, effectiveUserAnswers);
        Set<String> answeredGapTexts = answeredGapTextsFromAnswers(gaps, effectiveUserAnswers);
        List<String> recentChapters = recentChapterWindow(projectId, chapter, 2);
        List<Map<String, Object>> memoryEvidence = loadMemoryEvidence(projectId, recentChapters, seedEntities, chapterGoal);

        List<ContextItem> criticalItems = selectEngine.deterministicSelect(projectId, "writer");
        String combinedQuery = String.join(" ", seedEntities) + " " + (chapterGoal != null ? chapterGoal : "");
        List<ContextItem> seedRelevantItems = selectEngine.retrievalSelect(
                projectId, combinedQuery.trim(), List.of("character", "world", "fact", "text_chunk"), 24
        );

        List<String> characterCards = new ArrayList<>();
        List<String> worldCards = new ArrayList<>();
        List<Map<String, Object>> textChunks = new ArrayList<>();
        List<String> facts = new ArrayList<>();
        List<Map<String, Object>> evidenceItems = new ArrayList<>();
        List<Map<String, Object>> retrievalRequests = new ArrayList<>();
        Set<String> evidenceKeys = new LinkedHashSet<>();

        for (ContextItem item : criticalItems) {
            String type = item.getType() != null ? item.getType().getValue() : "";
            if ("character_card".equals(type) && characterCards.size() < 10) {
                characterCards.add(item.getContent());
            } else if ("world_card".equals(type) && worldCards.size() < 10) {
                worldCards.add(item.getContent());
            }
        }

        List<Map<String, Object>> unresolvedGaps = new ArrayList<>();
        for (Map<String, Object> gap : gaps) {
            @SuppressWarnings("unchecked")
            List<String> queries = (List<String>) gap.getOrDefault("queries", List.of());
            String gapText = String.valueOf(gap.getOrDefault("text", "")).trim();
            String gapKind = String.valueOf(gap.getOrDefault("kind", "")).trim();
            if (!gapText.isBlank() && answeredGapTexts.contains(gapText) && "character_state".equals(gapKind)) {
                retrievalRequests.add(Map.of(
                        "gap", gap,
                        "queries", queries,
                        "types", Map.of(),
                        "count", 0,
                        "support_score", 1.0,
                        "top_sources", List.of(),
                        "skipped", true,
                        "reason", "answered_gap_skip_retrieval"
                ));
                continue;
            }
            String gapQuery = queries.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isBlank())
                    .collect(java.util.stream.Collectors.joining(" "));
            List<Map<String, Object>> gapCandidates = new ArrayList<>();
            gapCandidates.addAll(filterMemoryEvidence(memoryEvidence, queries));
            if (!gapQuery.isBlank()) {
                List<ContextItem> gapItems = selectEngine.retrievalSelect(
                        projectId, gapQuery, List.of("character", "world", "fact", "text_chunk"), 16
                );
                for (ContextItem item : gapItems) {
                    Map<String, Object> evidence = toEvidenceItem(item);
                    if (evidence != null) {
                        gapCandidates.add(evidence);
                    }
                }
                List<Map<String, Object>> chunkHits = draftStorage.searchTextChunks(
                        projectId,
                        gapQuery,
                        8,
                        recentChapters.isEmpty() ? null : recentChapters,
                        List.of(chapter),
                        false
                );
                for (Map<String, Object> chunk : chunkHits) {
                    gapCandidates.add(toChunkEvidenceItem(chunk));
                }
            }

            Map<String, Map<String, Integer>> quotas = buildGapQuotas(String.valueOf(gap.getOrDefault("kind", "")));
            List<Map<String, Object>> selectedGapItems = applyTypeQuotas(gapCandidates, quotas, 10);
            for (Map<String, Object> item : selectedGapItems) {
                addEvidenceItem(
                        evidenceItems,
                        evidenceKeys,
                        String.valueOf(item.getOrDefault("id", "")),
                        String.valueOf(item.getOrDefault("type", "")),
                        String.valueOf(item.getOrDefault("text", "")),
                        item.get("score") instanceof Number n ? n.doubleValue() : 0.0,
                        item.get("source") instanceof Map<?, ?> source ? castStringObjectMap(source) : Map.of()
                );
            }

            double support = scoreGapSupport(queries, selectedGapItems);
            retrievalRequests.add(Map.of(
                    "gap", gap,
                    "queries", queries,
                    "types", countTypesInEvidence(selectedGapItems),
                    "count", selectedGapItems.size(),
                    "support_score", support,
                    "top_sources", extractTopSources(selectedGapItems, 3)
            ));
            if (support < 0.85) {
                Map<String, Object> unresolved = new LinkedHashMap<>(gap);
                unresolved.put("support_score", support);
                unresolvedGaps.add(unresolved);
            }
        }

        if (evidenceItems.isEmpty() && !combinedQuery.isBlank()) {
            for (ContextItem item : seedRelevantItems) {
                Map<String, Object> evidence = toEvidenceItem(item);
                if (evidence != null) {
                    addEvidenceItem(
                            evidenceItems,
                            evidenceKeys,
                            String.valueOf(evidence.getOrDefault("id", "")),
                            String.valueOf(evidence.getOrDefault("type", "")),
                            String.valueOf(evidence.getOrDefault("text", "")),
                            evidence.get("score") instanceof Number n ? n.doubleValue() : 0.0,
                            evidence.get("source") instanceof Map<?, ?> source ? castStringObjectMap(source) : Map.of()
                    );
                }
            }
            for (Map<String, Object> item : filterMemoryEvidence(memoryEvidence, List.of(combinedQuery))) {
                addEvidenceItem(
                        evidenceItems,
                        evidenceKeys,
                        String.valueOf(item.getOrDefault("id", "")),
                        String.valueOf(item.getOrDefault("type", "")),
                        String.valueOf(item.getOrDefault("text", "")),
                        item.get("score") instanceof Number n ? n.doubleValue() : 0.0,
                        item.get("source") instanceof Map<?, ?> source ? castStringObjectMap(source) : Map.of()
                );
            }
        }

        for (Map<String, Object> item : answerEvidence) {
            addEvidenceItem(
                    evidenceItems,
                    evidenceKeys,
                    String.valueOf(item.getOrDefault("id", "")),
                    String.valueOf(item.getOrDefault("type", "")),
                    String.valueOf(item.getOrDefault("text", "")),
                    item.get("score") instanceof Number n ? n.doubleValue() : 1.0,
                    item.get("source") instanceof Map<?, ?> source ? castStringObjectMap(source) : Map.of()
            );
        }

        List<Map<String, Object>> finalEvidenceItems = applyTypeQuotas(evidenceItems, buildGlobalWriterQuotas(), 12);
        populateContextCollections(finalEvidenceItems, characterCards, worldCards, facts, textChunks);

        String workingMemory = buildWorkingMemory(
                chapterGoal, sceneBrief, characterCards, worldCards,
                textChunks.stream().map(item -> String.valueOf(item.getOrDefault("text", ""))).toList(),
                facts,
                seedRelevantItems
        );

        payload.put("seed_entities", seedEntities);
        payload.put("character_cards", characterCards);
        payload.put("world_cards", worldCards);
        payload.put("text_chunks", textChunks);
        payload.put("facts", facts);
        payload.put("evidence_pack", Map.of(
                "items", finalEvidenceItems,
                "stats", Map.of(
                        "total", finalEvidenceItems.size(),
                        "types", countTypesInEvidence(finalEvidenceItems),
                        "queries", gaps.stream()
                                .flatMap(gap -> ((List<String>) gap.getOrDefault("queries", List.of())).stream())
                                .filter(Objects::nonNull)
                                .distinct()
                                .toList(),
                        "top_sources", extractTopSources(finalEvidenceItems, 3)
                )
        ));
        payload.put("retrieval_requests", retrievalRequests);
        payload.put("unresolved_gaps", unresolvedGaps.stream().limit(6).toList());
        if (workingMemory != null && !workingMemory.isBlank()) {
            payload.put("working_memory", workingMemory);
        }
        return payload;
    }

    private void addEvidenceItem(List<Map<String, Object>> evidenceItems, Set<String> evidenceKeys, String id,
                                 String type, String text, double score, Map<String, Object> source) {
        if (text == null || text.isBlank()) {
            return;
        }
        String key = (id != null && !id.isBlank() ? id : type + ":" + text.hashCode());
        if (!evidenceKeys.add(key)) {
            return;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", key);
        item.put("type", type);
        item.put("text", text.length() > 500 ? text.substring(0, 500) + "..." : text);
        item.put("score", score);
        item.put("source", source != null ? source : Map.of());
        evidenceItems.add(item);
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
        map.put("text", item.getContent().trim());
        map.put("score", item.getRelevanceScore());
        map.put("source", item.getMetadata() != null ? new LinkedHashMap<>(item.getMetadata()) : Map.of());
        return map;
    }

    private Map<String, Object> toChunkEvidenceItem(Map<String, Object> chunk) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", "chunk:" + chunk.getOrDefault("chapter", "") + ":" + chunk.getOrDefault("chunk_index", 0));
        map.put("type", "text_chunk");
        map.put("text", String.valueOf(chunk.getOrDefault("text", "")));
        map.put("score", chunk.get("score") instanceof Number n ? n.doubleValue() : 0.0);
        map.put("source", chunk.get("source") instanceof Map<?, ?> source ? castStringObjectMap(source) : Map.of("chapter", chunk.getOrDefault("chapter", "")));
        return map;
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

    private List<Map<String, Object>> loadMemoryEvidence(String projectId, List<String> recentChapters,
                                                         List<String> seedEntities, String chapterGoal) {
        List<String> chapters = new ArrayList<>();
        if (recentChapters != null) chapters.addAll(recentChapters);
        if (!chapters.contains(currentChapter) && currentChapter != null) {
            chapters.add(currentChapter);
        }
        List<String> queries = new ArrayList<>();
        if (seedEntities != null) queries.addAll(seedEntities);
        if (chapterGoal != null && !chapterGoal.isBlank()) queries.add(chapterGoal);

        List<Map<String, Object>> items = new ArrayList<>();
        for (String chapter : chapters.stream().filter(Objects::nonNull).distinct().toList()) {
            Map<String, Object> pack = memoryPackStorage.readPack(projectId, chapter);
            if (pack == null || pack.isEmpty()) {
                continue;
            }
            items.addAll(extractMemoryItems(chapter, pack, queries));
        }
        return items;
    }

    private List<Map<String, Object>> filterMemoryEvidence(List<Map<String, Object>> items, List<String> queries) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        if (queries == null || queries.isEmpty()) {
            return items;
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : items) {
            double score = scoreGapSupport(queries, List.of(item));
            if (score <= 0) {
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>(item);
            copy.put("score", Math.max(
                    copy.get("score") instanceof Number n ? n.doubleValue() : 0.0,
                    score
            ));
            result.add(copy);
        }
        result.sort((a, b) -> Double.compare(
                ((Number) b.getOrDefault("score", 0.0)).doubleValue(),
                ((Number) a.getOrDefault("score", 0.0)).doubleValue()
        ));
        return result.size() > 6 ? new ArrayList<>(result.subList(0, 6)) : result;
    }

    private List<Map<String, Object>> extractMemoryItems(String chapter, Map<String, Object> pack, List<String> queries) {
        List<Map<String, Object>> items = new ArrayList<>();
        Object payloadObj = pack.get("payload");
        if (payloadObj instanceof Map<?, ?> payload) {
            String workingMemory = stringValue(payload, "working_memory", "");
            if (!workingMemory.isBlank()) {
                items.add(memoryItem("memory:" + chapter + ":working_memory", chapter, workingMemory, "working_memory", queries));
            }
            Object evidencePack = payload.get("evidence_pack");
            if (evidencePack instanceof Map<?, ?> ep && ep.get("items") instanceof List<?> evidenceItems) {
                int count = 0;
                for (Object item : evidenceItems) {
                    if (item instanceof Map<?, ?> ev) {
                        String text = stringValue(ev, "text", "");
                        if (!text.isBlank()) {
                            items.add(memoryItem("memory:" + chapter + ":evidence:" + count, chapter, text, "evidence_pack", queries));
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
                items.add(memoryItem("memory:" + chapter + ":summary", chapter, summary, "chapter_digest", queries));
            }
            String tail = stringValue(digest, "tail_excerpt", "");
            if (!tail.isBlank()) {
                items.add(memoryItem("memory:" + chapter + ":tail", chapter, tail, "tail_excerpt", queries));
            }
        }
        return items;
    }

    private Map<String, Object> memoryItem(String id, String chapter, String text, String field, List<String> queries) {
        return new LinkedHashMap<>(Map.of(
                "id", id,
                "type", "memory",
                "text", text,
                "score", scoreText(queries, text),
                "source", Map.of("chapter", chapter, "field", field)
        ));
    }

    private List<String> extractSeedEntities(SceneBrief sceneBrief, String chapterGoal, List<String> characterNames) {
        LinkedHashSet<String> entities = new LinkedHashSet<>();
        if (characterNames != null) {
            characterNames.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isBlank()).forEach(entities::add);
        }
        if (sceneBrief != null && sceneBrief.getCharacters() != null) {
            for (Map<String, String> character : sceneBrief.getCharacters()) {
                String name = character.get("name");
                if (name != null && !name.isBlank()) {
                    entities.add(name.trim());
                }
            }
        }
        String text = (chapterGoal != null ? chapterGoal : "") + "\n" + (sceneBrief != null ? String.valueOf(sceneBrief.getGoal()) : "");
        Matcher matcher = Pattern.compile("[\\p{IsHan}]{2,6}|[A-Za-z][A-Za-z0-9_\\-]{2,}").matcher(text);
        while (matcher.find() && entities.size() < 10) {
            String term = matcher.group().trim();
            if (!term.isBlank()) {
                entities.add(term);
            }
        }
        return new ArrayList<>(entities);
    }

    private List<Map<String, Object>> buildAnswerEvidenceItems(String chapter, List<Map<String, String>> userAnswers) {
        if (userAnswers == null || userAnswers.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        int index = 0;
        for (Map<String, String> answer : userAnswers) {
            if (answer == null) continue;
            String answerText = safeAnswerText(answer.get("answer"));
            if (answerText.isBlank() || isUnknownAnswer(answerText)) {
                continue;
            }
            String question = safeAnswerText(firstNonBlank(answer.get("question"), answer.get("text"), answer.get("key")));
            items.add(new LinkedHashMap<>(Map.of(
                    "id", "answer:" + chapter + ":" + index++,
                    "type", "answer",
                    "text", (question.isBlank() ? "" : question + "\n") + answerText,
                    "score", 1.2,
                    "source", Map.of("chapter", chapter, "field", "user_answer")
            )));
        }
        return items;
    }

    private Set<String> answeredGapTextsFromAnswers(List<Map<String, Object>> gaps, List<Map<String, String>> userAnswers) {
        Set<String> answered = new LinkedHashSet<>();
        if (gaps == null || gaps.isEmpty() || userAnswers == null || userAnswers.isEmpty()) {
            return answered;
        }
        for (Map<String, Object> gap : gaps) {
            String gapText = String.valueOf(gap.getOrDefault("text", "")).trim();
            @SuppressWarnings("unchecked")
            List<String> queries = (List<String>) gap.getOrDefault("queries", List.of());
            if (gapText.isBlank()) continue;
            for (Map<String, String> answer : userAnswers) {
                if (answer == null) continue;
                String answerText = safeAnswerText(answer.get("answer"));
                if (answerText.isBlank() || isUnknownAnswer(answerText)) continue;
                String question = safeAnswerText(firstNonBlank(answer.get("question"), answer.get("text"), answer.get("key")));
                if (!question.isBlank() && (question.contains(gapText) || gapText.contains(question))) {
                    answered.add(gapText);
                    break;
                }
                for (String query : queries) {
                    if (query != null && !query.isBlank() && !question.isBlank()
                            && (question.contains(query) || query.contains(question))) {
                        answered.add(gapText);
                        break;
                    }
                }
                if (answered.contains(gapText)) break;
            }
        }
        return answered;
    }

    private String safeAnswerText(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isUnknownAnswer(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        return normalized.isBlank()
                || normalized.equals("unknown")
                || normalized.equals("n/a")
                || normalized.equals("不知道")
                || normalized.equals("不清楚")
                || normalized.equals("未定");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private List<Map<String, String>> loadPersistedUserAnswers(String projectId, String chapter) {
        Map<String, Object> pack = memoryPackStorage.readPack(projectId, chapter);
        if (pack == null || pack.isEmpty()) {
            return List.of();
        }
        Object payloadObj = pack.get("payload");
        if (!(payloadObj instanceof Map<?, ?> payload)) {
            return List.of();
        }
        Object rawAnswers = payload.get("user_answers");
        if (!(rawAnswers instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, String>> answers = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, String> answer = new LinkedHashMap<>();
                putIfNotBlank(answer, "question", stringValue(map, "question", ""));
                putIfNotBlank(answer, "text", stringValue(map, "text", ""));
                putIfNotBlank(answer, "key", stringValue(map, "key", ""));
                putIfNotBlank(answer, "answer", stringValue(map, "answer", ""));
                if (!answer.isEmpty()) {
                    answers.add(answer);
                }
            }
        }
        return answers;
    }

    private void putIfNotBlank(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private List<Map<String, Object>> buildResearchGaps(SceneBrief sceneBrief, String chapterGoal, List<String> seedEntities) {
        List<Map<String, Object>> gaps = new ArrayList<>();
        String effectiveGoal = chapterGoal != null && !chapterGoal.isBlank()
                ? chapterGoal
                : (sceneBrief != null ? String.valueOf(sceneBrief.getGoal()) : "");
        if (!effectiveGoal.isBlank()) {
            gaps.add(Map.of(
                    "kind", "plot_point",
                    "text", effectiveGoal,
                    "queries", List.of(effectiveGoal)
            ));
        }
        if (sceneBrief != null && sceneBrief.getWorldConstraints() != null) {
            sceneBrief.getWorldConstraints().stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .limit(2)
                    .forEach(item -> gaps.add(Map.of(
                            "kind", "world_rule",
                            "text", item,
                            "queries", List.of(item)
                    )));
        }
        if (!seedEntities.isEmpty()) {
            gaps.add(Map.of(
                    "kind", "character_state",
                    "text", "角色与设定一致性",
                    "queries", seedEntities.stream().limit(4).toList()
            ));
        }
        return gaps;
    }

    private List<String> recentChapterWindow(String projectId, String chapter, int window) {
        return draftStorage.listChapterSummaries(projectId, null).stream()
                .filter(summary -> compareChapterIds(summary.getChapter(), chapter) < 0)
                .sorted((a, b) -> compareChapterIds(b.getChapter(), a.getChapter()))
                .limit(window)
                .map(ChapterSummary::getChapter)
                .toList();
    }

    private double scoreGapSupport(List<String> queries, List<Map<String, Object>> evidenceItems) {
        if (queries == null || queries.isEmpty() || evidenceItems == null || evidenceItems.isEmpty()) {
            return 0.0;
        }
        Set<String> queryTerms = new LinkedHashSet<>();
        for (String query : queries) {
            if (query == null) continue;
            Matcher matcher = Pattern.compile("[\\p{IsHan}]{2,4}|[A-Za-z0-9_]{2,}").matcher(query.toLowerCase());
            while (matcher.find()) {
                queryTerms.add(matcher.group());
            }
        }
        if (queryTerms.isEmpty()) {
            return 0.0;
        }
        double best = 0.0;
        for (Map<String, Object> item : evidenceItems) {
            String text = String.valueOf(item.getOrDefault("text", "")).toLowerCase();
            int overlap = 0;
            for (String term : queryTerms) {
                if (text.contains(term)) {
                    overlap++;
                }
            }
            best = Math.max(best, overlap / (double) queryTerms.size());
        }
        return best;
    }

    private double scoreText(List<String> queries, String text) {
        if (queries == null || queries.isEmpty() || text == null || text.isBlank()) {
            return 0.0;
        }
        return scoreGapSupport(
                queries,
                List.of(Map.of("text", text))
        );
    }

    private Map<String, Map<String, Integer>> buildGapQuotas(String gapKind) {
        Map<String, Map<String, Integer>> quotas = new LinkedHashMap<>();
        quotas.put("fact", quota(2, 6));
        quotas.put("text_chunk", quota(2, 6));
        quotas.put("character", quota(0, 4));
        quotas.put("world", quota(0, 3));
        quotas.put("memory", quota(0, 3));
        if ("world_rule".equals(gapKind)) {
            quotas.put("world", quota(1, 4));
            quotas.put("fact", quota(1, 4));
        } else if ("character_state".equals(gapKind)) {
            quotas.put("character", quota(1, 5));
            quotas.put("memory", quota(1, 4));
        }
        return quotas;
    }

    private Map<String, Map<String, Integer>> buildGlobalWriterQuotas() {
        Map<String, Map<String, Integer>> quotas = new LinkedHashMap<>();
        quotas.put("fact", quota(2, 6));
        quotas.put("text_chunk", quota(3, 6));
        quotas.put("character", quota(0, 4));
        quotas.put("world", quota(0, 3));
        quotas.put("memory", quota(0, 3));
        return quotas;
    }

    private Map<String, Integer> quota(int min, int max) {
        return Map.of("min", min, "max", max);
    }

    private List<Map<String, Object>> applyTypeQuotas(List<Map<String, Object>> scored,
                                                      Map<String, Map<String, Integer>> quotas,
                                                      int limit) {
        if (scored == null || scored.isEmpty() || limit <= 0) {
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
            List<Map<String, Object>> candidates = byType.getOrDefault(type, List.of());
            for (int i = 0; i < Math.min(min, candidates.size()) && selected.size() < limit; i++) {
                Map<String, Object> item = candidates.get(i);
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

    private Map<String, Integer> countTypesInEvidence(List<Map<String, Object>> items) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            String type = String.valueOf(item.getOrDefault("type", ""));
            if (!type.isBlank()) {
                counts.put(type, counts.getOrDefault(type, 0) + 1);
            }
        }
        return counts;
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

    private void populateContextCollections(List<Map<String, Object>> items,
                                            List<String> characterCards,
                                            List<String> worldCards,
                                            List<String> facts,
                                            List<Map<String, Object>> textChunks) {
        for (Map<String, Object> item : items) {
            String type = String.valueOf(item.getOrDefault("type", ""));
            String text = String.valueOf(item.getOrDefault("text", ""));
            switch (type) {
                case "character" -> {
                    if (!text.isBlank() && characterCards.size() < 10 && !characterCards.contains(text)) characterCards.add(text);
                }
                case "world" -> {
                    if (!text.isBlank() && worldCards.size() < 10 && !worldCards.contains(text)) worldCards.add(text);
                }
                case "fact" -> {
                    if (!text.isBlank() && facts.size() < 20 && !facts.contains(text)) facts.add(text);
                }
                case "text_chunk" -> {
                    if (!text.isBlank() && textChunks.size() < 8) {
                        Map<String, Object> source = item.get("source") instanceof Map<?, ?> map ? castStringObjectMap(map) : Map.of();
                        textChunks.add(Map.of(
                                "chapter", String.valueOf(source.getOrDefault("chapter", "")),
                                "text", text,
                                "score", item.getOrDefault("score", 0.0)
                        ));
                    }
                }
            }
        }
    }

    private int createCardsFromProposals(String projectId, List<Map<String, Object>> proposals, boolean overwrite) {
        int created = 0;
        if (proposals == null) {
            return 0;
        }
        for (Map<String, Object> item : proposals) {
            if (item == null) {
                continue;
            }
            String type = String.valueOf(item.getOrDefault("type", "")).trim().toLowerCase();
            String name = String.valueOf(item.getOrDefault("name", "")).trim();
            if (name.isBlank()) {
                continue;
            }
            try {
                if ("world".equals(type)) {
                    if (!overwrite && cardStorage.getWorldCard(projectId, name).isPresent()) {
                        continue;
                    }
                    com.wenshape.model.entity.WorldCard card = com.wenshape.model.entity.WorldCard.builder()
                            .name(name)
                            .description(String.valueOf(item.getOrDefault("description", item.getOrDefault("source_text", ""))))
                            .category("analysis")
                            .build();
                    cardStorage.saveWorldCard(projectId, card);
                    created++;
                }
            } catch (Exception e) {
                log.warn("createCardsFromProposals 失败: {}", e.getMessage());
            }
        }
        return created;
    }

    /**
     * 构建简化版 working_memory（对齐 Python research loop 核心输出）
     *
     * Python 版通过多轮 LLM 自检循环生成 working_memory，Java 版用确定性检索结果
     * 直接整合为结构化文本，避免额外 LLM 调用，同时保证 working_memory 字段有内容。
     *
     * 优先级：高分 text_chunks > 高分 facts > 角色卡摘要 > 世界观卡摘要
     */
    private String buildWorkingMemory(String chapterGoal, SceneBrief sceneBrief,
                                       List<String> characterCards, List<String> worldCards,
                                       List<String> textChunks, List<String> facts,
                                       List<ContextItem> relevantItems) {
        StringBuilder sb = new StringBuilder();

        // 章节目标
        String goal = chapterGoal != null && !chapterGoal.isBlank() ? chapterGoal
                : (sceneBrief != null && sceneBrief.getGoal() != null ? sceneBrief.getGoal() : "");
        if (!goal.isBlank()) {
            sb.append("【章节目标】\n").append(goal).append("\n\n");
        }

        // 高分 text_chunks（最相关的原文片段，优先级最高）
        List<ContextItem> highScoreChunks = relevantItems.stream()
                .filter(i -> i.getType() != null && "text_chunk".equals(i.getType().getValue()))
                .filter(i -> i.getRelevanceScore() >= 0.1)
                .sorted((a, b) -> Double.compare(b.getRelevanceScore(), a.getRelevanceScore()))
                .limit(3)
                .toList();
        if (!highScoreChunks.isEmpty()) {
            sb.append("【相关原文片段】\n");
            for (ContextItem item : highScoreChunks) {
                String content = item.getContent();
                if (content != null && !content.isBlank()) {
                    // 截断过长内容
                    String truncated = content.length() > 400 ? content.substring(0, 400) + "…" : content;
                    sb.append(truncated).append("\n\n");
                }
            }
        }

        // 高分 facts（已确立事实）
        List<ContextItem> highScoreFacts = relevantItems.stream()
                .filter(i -> i.getType() != null && "fact".equals(i.getType().getValue()))
                .filter(i -> i.getRelevanceScore() >= 0.1)
                .sorted((a, b) -> Double.compare(b.getRelevanceScore(), a.getRelevanceScore()))
                .limit(5)
                .toList();
        if (!highScoreFacts.isEmpty()) {
            sb.append("【相关已确立事实】\n");
            for (ContextItem item : highScoreFacts) {
                String content = item.getContent();
                if (content != null && !content.isBlank()) {
                    sb.append("- ").append(content.length() > 200 ? content.substring(0, 200) + "…" : content).append("\n");
                }
            }
            sb.append("\n");
        }

        // 涉及角色摘要（取前2个角色卡的前200字）
        if (!characterCards.isEmpty()) {
            sb.append("【涉及角色】\n");
            characterCards.stream().limit(2).forEach(card -> {
                String snippet = card.length() > 200 ? card.substring(0, 200) + "…" : card;
                sb.append(snippet).append("\n\n");
            });
        }

        // 世界观约束摘要（取前1个世界卡的前200字）
        if (!worldCards.isEmpty()) {
            sb.append("【世界观约束】\n");
            String card = worldCards.get(0);
            sb.append(card.length() > 200 ? card.substring(0, 200) + "…" : card).append("\n\n");
        }

        String result = sb.toString().trim();
        // 内容太少（只有目标，没有实质证据）时不返回，避免误导
        return result.length() > goal.length() + 30 ? result : null;
    }

    private int compareChapterIds(String a, String b) {
        try {
            int[] pa = parseChapterId(a);
            int[] pb = parseChapterId(b);
            if (pa[0] != pb[0]) return Integer.compare(pa[0], pb[0]);
            return Integer.compare(pa[1], pb[1]);
        } catch (Exception e) {
            return (a != null ? a : "").compareTo(b != null ? b : "");
        }
    }

    private int[] parseChapterId(String chapter) {
        if (chapter == null) return new int[]{1, 0};
        String upper = chapter.toUpperCase();
        int vIdx = upper.indexOf('V');
        int cIdx = upper.indexOf('C');
        if (vIdx >= 0 && cIdx > vIdx) {
            int vol = Integer.parseInt(upper.substring(vIdx + 1, cIdx));
            int ch = Integer.parseInt(upper.substring(cIdx + 1));
            return new int[]{vol, ch};
        }
        return new int[]{1, 0};
    }

    private void saveDraftReview(String projectId, String chapter, Draft draft) {
        if (draft == null || draft.getContent() == null || draft.getContent().isBlank()) {
            return;
        }
        try {
            String version = draft.getVersion() != null && !draft.getVersion().isBlank() ? draft.getVersion() : "current";
            draftStorage.saveReview(projectId, chapter, editorAgent.reviewDraft(projectId, chapter, version, draft.getContent()));
        } catch (Exception e) {
            log.warn("saveDraftReview 失败: {}", e.getMessage());
        }
    }

    private void persistMemoryPack(String projectId, String chapter, Map<String, Object> context, String source) {
        if (projectId == null || projectId.isBlank() || chapter == null || chapter.isBlank() || context == null) {
            return;
        }
        try {
            Map<String, Object> pack = new LinkedHashMap<>();
            pack.put("project_id", projectId);
            pack.put("chapter", chapter);
            pack.put("built_at", java.time.Instant.now().toString());
            pack.put("source", source);

            Map<String, Object> payload = new LinkedHashMap<>();
            for (String key : List.of(
                    "chapterGoal", "working_memory", "evidence_pack", "unresolved_gaps", "retrieval_requests",
                    "seed_entities", "text_chunks", "facts", "character_cards", "world_cards", "user_answers", "userFeedback"
            )) {
                if (context.containsKey(key)) {
                    payload.put(key, context.get(key));
                }
            }
            pack.put("payload", payload);
            pack.put("chapter_digest", buildChapterDigest(projectId, chapter));
            memoryPackStorage.writePack(projectId, chapter, pack);
        } catch (Exception e) {
            log.warn("persistMemoryPack 失败: {}", e.getMessage());
        }
    }

    /**
     * 构建 context_debug 字段（对齐 Python 版返回给前端的调试信息）
     */
    private Map<String, Object> buildContextDebug(String projectId, String chapter) {
        Map<String, Object> debug = new LinkedHashMap<>();
        try {
            debug.put("character_cards", cardStorage.listCharacterCards(projectId).size());
            debug.put("world_cards", cardStorage.listWorldCards(projectId).size());
            debug.put("facts", canonStorage.listFacts(projectId).size());
            debug.put("chapter_summaries", draftStorage.listChapterSummaries(projectId, null).size());
            debug.put("chapter", chapter);
            debug.put("project_id", projectId);
            try {
                Map<String, Object> pack = memoryPackStorage.readPack(projectId, chapter);
                if (pack != null) {
                    debug.put("memory_pack_built_at", pack.getOrDefault("built_at", ""));
                    debug.put("memory_pack_source", pack.getOrDefault("source", ""));
                    Object payloadObj = pack.get("payload");
                    if (payloadObj instanceof Map<?, ?> payload) {
                        Object gaps = payload.get("unresolved_gaps");
                        if (gaps instanceof List<?> list) {
                            debug.put("unresolved_gaps", list.size());
                        }
                        Object evidencePack = payload.get("evidence_pack");
                        if (evidencePack instanceof Map<?, ?> ep && ep.get("items") instanceof List<?> items) {
                            debug.put("evidence_items", items.size());
                        }
                    }
                }
            } catch (Exception ignored) {}
        } catch (Exception e) {
            log.warn("buildContextDebug 失败: {}", e.getMessage());
        }
        return debug;
    }

    /**
     * 构建 memory pack payload（简化版，对齐 Python _prepare_memory_pack_payload 核心结构）
     */
    private Map<String, Object> buildMemoryPackPayload(String projectId, String chapter) {
        Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("project_id", projectId);
        pack.put("chapter", chapter);
        pack.put("built_at", java.time.Instant.now().toString());
        pack.put("source", "writer");
        try {
            SceneBrief sceneBrief = draftStorage.getSceneBrief(projectId, chapter).orElse(null);
            String goal = sceneBrief != null && sceneBrief.getGoal() != null ? sceneBrief.getGoal() : "";
            Map<String, Object> payload = prepareWriterContext(projectId, chapter, goal, sceneBrief, List.of(), List.of());
            payload.remove("sceneBrief");
            payload.remove("styleCard");
            payload.remove("previousSummaries");
            pack.put("payload", payload);
            pack.put("chapter_digest", buildChapterDigest(projectId, chapter));
            pack.put("character_cards_count", cardStorage.listCharacterCards(projectId).size());
            pack.put("world_cards_count", cardStorage.listWorldCards(projectId).size());
            pack.put("facts_count", canonStorage.listFacts(projectId).size());
        } catch (Exception e) {
            log.warn("buildMemoryPackPayload 失败: {}", e.getMessage());
        }
        return pack;
    }

    private Map<String, Object> buildChapterDigest(String projectId, String chapter) {
        Map<String, Object> digest = new LinkedHashMap<>();
        draftStorage.getChapterSummary(projectId, chapter).ifPresent(summary -> {
            digest.put("summary", summary.getBriefSummary() != null ? summary.getBriefSummary() : "");
            if (summary.getTitle() != null && !summary.getTitle().isBlank()) {
                digest.put("title", summary.getTitle());
            }
        });
        draftStorage.getFinalDraft(projectId, chapter).ifPresent(text -> {
            String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
            digest.put("tail_excerpt", normalized.length() > 900 ? normalized.substring(normalized.length() - 900) : normalized);
        });
        if (!digest.containsKey("tail_excerpt")) {
            draftStorage.getLatestDraft(projectId, chapter).ifPresent(draft -> {
                String text = draft.getContent() != null ? draft.getContent() : "";
                String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
                digest.put("tail_excerpt", normalized.length() > 900 ? normalized.substring(normalized.length() - 900) : normalized);
            });
        }
        try {
            List<String> topCharacters = cardStorage.listCharacterCards(projectId).stream().limit(6).toList();
            if (!topCharacters.isEmpty()) {
                digest.put("top_characters", topCharacters);
            }
            List<String> topWorld = cardStorage.listWorldCards(projectId).stream().limit(6).toList();
            if (!topWorld.isEmpty()) {
                digest.put("top_world", topWorld);
            }
        } catch (Exception e) {
            log.warn("buildChapterDigest 失败: {}", e.getMessage());
        }
        return digest;
    }
}
