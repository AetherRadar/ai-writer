package com.wenshape.controller;

import com.wenshape.agent.EditorAgent;
import com.wenshape.dto.*;
import com.wenshape.orchestrator.Orchestrator;
import com.wenshape.orchestrator.OrchestratorPool;
import com.wenshape.storage.DraftStorage;
import com.wenshape.storage.MemoryPackStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话控制器 - 写作会话管理
 */
@Slf4j
@RestController
@RequestMapping("/projects/{projectId}/session")
@RequiredArgsConstructor
public class SessionController {
    
    private final OrchestratorPool orchestratorPool;
    private final EditorAgent editorAgent;
    private final DraftStorage draftStorage;
    private final MemoryPackStorage memoryPackStorage;
    
    /**
     * 开始写作会话
     * POST /projects/{projectId}/session/start
     */
    @PostMapping("/start")
    public Map<String, Object> startSession(@PathVariable String projectId,
                                             @RequestBody StartSessionRequest request) {
        log.info("Starting session for project: {}, chapter: {}", projectId, request.getChapter());
        
        Orchestrator orchestrator = orchestratorPool.getOrchestrator(projectId);
        orchestrator.setLanguage(request.getLanguage());
        
        return orchestrator.startSession(
                projectId,
                request.getChapter(),
                request.getChapterTitle(),
                request.getChapterGoal(),
                request.getTargetWordCount() != null ? request.getTargetWordCount() : 3000,
                request.getCharacterNames()
        );
    }
    
    /**
     * 获取会话状态
     * GET /projects/{projectId}/session/status
     */
    @GetMapping("/status")
    public Map<String, Object> getStatus(@PathVariable String projectId) {
        Orchestrator orchestrator = orchestratorPool.getOrchestrator(projectId);
        Map<String, Object> status = orchestrator.getStatus();
        
        String statusProjectId = (String) status.get("project_id");
        if (statusProjectId == null || statusProjectId.isEmpty() || !statusProjectId.equals(projectId)) {
            return Map.of("status", "idle", "message", "No active session for this project");
        }
        
        return status;
    }
    
    /**
     * 提交用户反馈
     * POST /projects/{projectId}/session/feedback
     */
    @PostMapping("/feedback")
    public Map<String, Object> submitFeedback(@PathVariable String projectId,
                                               @RequestBody FeedbackRequest request) {
        log.info("Processing feedback for project: {}, chapter: {}, action: {}",
                projectId, request.getChapter(), request.getAction());
        
        Orchestrator orchestrator = orchestratorPool.getOrchestrator(projectId);
        
        return orchestrator.processFeedback(
                projectId,
                request.getChapter(),
                request.getFeedback(),
                request.getAction(),
                request.getRejectedEntities()
        );
    }
    
    /**
     * 编辑建议（差异修订）
     * POST /projects/{projectId}/session/edit-suggest
     */
    @PostMapping("/edit-suggest")
    public Map<String, Object> suggestEdit(@PathVariable String projectId,
                                            @RequestBody EditSuggestRequest request) {
        log.info("Edit suggest for project: {}, chapter: {}", projectId, request.getChapter());

        // 获取原始内容：优先用请求体里的 content，否则从存储读
        String originalContent = request.getContent();
        if (originalContent == null || originalContent.isBlank()) {
            originalContent = draftStorage.getFinalDraft(projectId, request.getChapter())
                    .orElse("");
        }
        if (originalContent.isBlank()) {
            return Map.of("success", false, "error", "No draft content found for chapter: " + request.getChapter());
        }

        String instruction = request.getInstruction();
        if (instruction == null || instruction.isBlank()) {
            return Map.of("success", false, "error", "instruction is required");
        }

        try {
            String revised;
            Map<String, Object> memoryPack = request.getChapter() != null
                    ? memoryPackStorage.readPack(projectId, request.getChapter())
                    : null;
            if (request.getSelectionStart() != null && request.getSelectionEnd() != null) {
                // 精确偏移选区编辑
                revised = editorAgent.suggestRevisionSelectionRange(
                        projectId,
                        originalContent,
                        request.getSelectionStart(),
                        request.getSelectionEnd(),
                        request.getSelectionText(),
                        instruction,
                        request.getRejectedEntities(),
                        memoryPack);
            } else if (request.getSelectionText() != null && !request.getSelectionText().isBlank()) {
                // 子串匹配选区编辑
                revised = editorAgent.suggestRevisionSelection(
                        projectId,
                        originalContent,
                        request.getSelectionText(),
                        instruction,
                        request.getRejectedEntities(),
                        memoryPack);
            } else {
                // 全文修订
                revised = editorAgent.suggestRevision(
                        projectId, originalContent, instruction, request.getRejectedEntities(), memoryPack);
            }

            // 检测是否产生了实际修改（对齐 Python normalize_for_compare 检测）
            String originalNorm = originalContent.replaceAll("\\s+", " ").trim();
            String revisedNorm = revised.replaceAll("\\s+", " ").trim();
            if (revisedNorm.equals(originalNorm)) {
                return Map.of("success", false,
                        "error", "未能生成可应用的差异修改：请在指令中复制粘贴要修改的原句/段落，或使用「选区编辑」进行精确定位。");
            }

            return Map.of(
                    "success", true,
                    "revised_content", revised,
                    "word_count", revised.length(),
                    "chapter", request.getChapter() != null ? request.getChapter() : ""
            );
        } catch (Exception e) {
            log.error("编辑建议失败", e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }
    
    /**
     * 回答预写问题 / 用户确认场景简报后触发写作
     * POST /projects/{projectId}/session/answer-questions
     */
    @PostMapping("/answer-questions")
    public Map<String, Object> answerQuestions(@PathVariable String projectId,
                                                @RequestBody AnswerQuestionsRequest request) {
        log.info("Answer questions / proceed to write for project: {}, chapter: {}", projectId, request.getChapter());

        Orchestrator orchestrator = orchestratorPool.getOrchestrator(projectId);
        orchestrator.setLanguage(request.getLanguage());

        // 将 answers 转为 Map 列表
        List<Map<String, String>> answers = new java.util.ArrayList<>();
        if (request.getAnswers() != null) {
            for (AnswerQuestionsRequest.QuestionAnswer qa : request.getAnswers()) {
                Map<String, String> m = new java.util.HashMap<>();
                if (qa.getType() != null) m.put("type", qa.getType());
                if (qa.getQuestion() != null) m.put("question", qa.getQuestion());
                if (qa.getKey() != null) m.put("key", qa.getKey());
                if (qa.getAnswer() != null) m.put("answer", qa.getAnswer());
                answers.add(m);
            }
        }

        return orchestrator.proceedToWrite(
                projectId,
                request.getChapter(),
                request.getChapterGoal(),
                request.getTargetWordCount() != null ? request.getTargetWordCount() : 3000,
                request.getCharacterNames(),
                answers
        );
    }
    
    /**
     * 取消会话
     * POST /projects/{projectId}/session/cancel
     */
    @PostMapping("/cancel")
    public Map<String, Object> cancelSession(@PathVariable String projectId) {
        log.info("Cancelling session for project: {}", projectId);
        
        Orchestrator orchestrator = orchestratorPool.getOrchestrator(projectId);
        return orchestrator.cancelSession(projectId);
    }
    
    /**
     * 分析章节
     * POST /projects/{projectId}/session/analyze
     */
    @PostMapping("/analyze")
    public Map<String, Object> analyzeChapter(@PathVariable String projectId,
                                               @RequestBody AnalyzeRequest request) {
        log.info("Analyzing chapter for project: {}, chapter: {}", projectId, request.getChapter());
        
        Orchestrator orchestrator = orchestratorPool.getOrchestrator(projectId);
        orchestrator.setLanguage(request.getLanguage());
        return orchestrator.analyzeChapter(
                projectId,
                request.getChapter(),
                request.getContent(),
                request.getChapterTitle()
        );
    }
    
    /**
     * 保存分析结果
     * POST /projects/{projectId}/session/save-analysis
     */
    @PostMapping("/save-analysis")
    public Map<String, Object> saveAnalysis(@PathVariable String projectId,
                                             @RequestBody Map<String, Object> request) {
        log.info("Saving analysis for project: {}", projectId);
        
        String chapter = (String) request.get("chapter");
        @SuppressWarnings("unchecked")
        Map<String, Object> analysis = (Map<String, Object>) request.get("analysis");
        boolean overwrite = Boolean.TRUE.equals(request.get("overwrite"));
        
        Orchestrator orchestrator = orchestratorPool.getOrchestrator(projectId);
        return orchestrator.saveAnalysis(projectId, chapter, analysis, overwrite);
    }
    
    /**
     * 批量分析章节
     * POST /projects/{projectId}/session/analyze-batch
     */
    @PostMapping("/analyze-batch")
    public Map<String, Object> analyzeBatch(@PathVariable String projectId,
                                             @RequestBody Map<String, Object> request) {
        log.info("Batch analyzing chapters for project: {}", projectId);
        
        @SuppressWarnings("unchecked")
        List<String> chapters = (List<String>) request.get("chapters");
        
        Orchestrator orchestrator = orchestratorPool.getOrchestrator(projectId);
        return orchestrator.analyzeBatch(projectId, chapters);
    }
    
    /**
     * 同步分析（分析并覆盖保存）
     * POST /projects/{projectId}/session/analyze-sync
     */
    @PostMapping("/analyze-sync")
    public Map<String, Object> analyzeSync(@PathVariable String projectId,
                                            @RequestBody Map<String, Object> request) {
        log.info("Sync analyzing chapters for project: {}", projectId);
        
        @SuppressWarnings("unchecked")
        List<String> chapters = (List<String>) request.get("chapters");
        
        Orchestrator orchestrator = orchestratorPool.getOrchestrator(projectId);
        return orchestrator.analyzeSync(projectId, chapters);
    }
    
    /**
     * 批量保存分析结果
     * POST /projects/{projectId}/session/save-analysis-batch
     */
    @PostMapping("/save-analysis-batch")
    public Map<String, Object> saveAnalysisBatch(@PathVariable String projectId,
                                                  @RequestBody Map<String, Object> request) {
        log.info("Batch saving analysis for project: {}", projectId);
        
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) request.get("items");
        boolean overwrite = Boolean.TRUE.equals(request.get("overwrite"));
        
        Orchestrator orchestrator = orchestratorPool.getOrchestrator(projectId);
        return orchestrator.saveAnalysisBatch(projectId, items, overwrite);
    }
    
    /**
     * 流式生成草稿（SSE）
     * GET /projects/{projectId}/session/stream-draft
     */
    @GetMapping(value = "/stream-draft", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamDraft(@PathVariable String projectId,
                                     @RequestParam String chapter) {
        log.info("Stream draft for project: {}, chapter: {}", projectId, chapter);

        Orchestrator orchestrator = orchestratorPool.getOrchestrator(projectId);

        // 从存储加载场景简报，并通过 Orchestrator 准备完整上下文
        return orchestrator.prepareAndStreamDraft(projectId, chapter)
                .map(chunk -> "data: " + chunk.replace("\n", "\\n") + "\n\n");
    }
}
