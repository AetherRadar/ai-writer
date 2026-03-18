package com.wenshape.controller;

import com.wenshape.agent.EditorAgent;
import com.wenshape.controller.BindingsController;
import com.wenshape.model.entity.ChapterSummary;
import com.wenshape.model.entity.Draft;
import com.wenshape.model.entity.ReviewResult;
import com.wenshape.model.entity.SceneBrief;
import com.wenshape.orchestrator.OrchestratorPool;
import com.wenshape.storage.CanonStorage;
import com.wenshape.storage.DraftStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 草稿管理接口
 */
@Slf4j
@RestController
@RequestMapping("/projects/{projectId}/drafts")
@RequiredArgsConstructor
public class DraftController {
    
    private final DraftStorage draftStorage;
    private final EditorAgent editorAgent;
    private final CanonStorage canonStorage;
    private final OrchestratorPool orchestratorPool;
    private final BindingsController bindingsController;
    
    @GetMapping("")
    public List<String> listChapters(@PathVariable String projectId) {
        return draftStorage.listChapters(projectId);
    }
    
    @GetMapping("/{chapter}")
    public Draft getLatestDraft(@PathVariable String projectId, @PathVariable String chapter) {
        return draftStorage.getLatestDraft(projectId, chapter)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Draft not found"));
    }
    
    @GetMapping("/{chapter}/final")
    public Map<String, Object> getFinalDraft(@PathVariable String projectId, @PathVariable String chapter) {
        String content = draftStorage.getFinalDraft(projectId, chapter)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Final draft not found"));
        return Map.of(
                "chapter", chapter,
                "content", content,
                "word_count", content.length()
        );
    }
    
    @PutMapping("/{chapter}")
    public Draft saveDraft(@PathVariable String projectId, @PathVariable String chapter,
                           @RequestBody DraftSaveRequest request) {
        try {
            return draftStorage.saveCurrentDraft(
                    projectId,
                    chapter,
                    request.content(),
                    request.wordCount(),
                    request.pendingConfirmations(),
                    true
            );
        } catch (IOException e) {
            log.error("保存草稿失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
    
    @DeleteMapping("/{chapter}")
    public Map<String, Object> deleteChapter(@PathVariable String projectId, @PathVariable String chapter) {
        try {
            boolean success = draftStorage.deleteChapter(projectId, chapter);
            if (!success) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Chapter not found");
            }

            // 级联删除：facts（按 introduced_in 匹配章节）
            Map<String, Object> cascade = new java.util.HashMap<>();
            try {
                List<com.wenshape.model.entity.Fact> facts = canonStorage.listFacts(projectId);
                int deleted = 0;
                for (com.wenshape.model.entity.Fact fact : facts) {
                    if (chapter.equals(fact.getIntroducedIn())) {
                        canonStorage.deleteFact(projectId, fact.getId());
                        deleted++;
                    }
                }
                cascade.put("facts_deleted", deleted);
            } catch (Exception e) {
                log.warn("级联删除 facts 失败: {}", e.getMessage());
            }

            return Map.of("success", true, "cascade", cascade);
        } catch (IOException e) {
            log.error("删除章节失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
    
    // ========== 场景简报 ==========
    
    @GetMapping("/{chapter}/scene-brief")
    public SceneBrief getSceneBrief(@PathVariable String projectId, @PathVariable String chapter) {
        return draftStorage.getSceneBrief(projectId, chapter)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Scene brief not found"));
    }
    
    @PutMapping("/{chapter}/scene-brief")
    public Map<String, Object> saveSceneBrief(@PathVariable String projectId, @PathVariable String chapter,
                                               @RequestBody SceneBrief brief) {
        try {
            brief.setChapter(chapter);
            draftStorage.saveSceneBrief(projectId, chapter, brief);
            return Map.of("success", true, "message", "Scene brief saved");
        } catch (IOException e) {
            log.error("保存场景简报失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
    
    // ========== 章节摘要 ==========
    
    @GetMapping("/summaries")
    public List<ChapterSummary> listChapterSummaries(@PathVariable String projectId,
                                                      @RequestParam(required = false) String volumeId) {
        return draftStorage.listChapterSummaries(projectId, volumeId);
    }
    
    @GetMapping("/{chapter}/summary")
    public ChapterSummary getChapterSummary(@PathVariable String projectId, @PathVariable String chapter) {
        return draftStorage.getChapterSummary(projectId, chapter)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chapter summary not found"));
    }
    
    @PostMapping("/{chapter}/summary")
    public Map<String, Object> saveChapterSummaryPost(@PathVariable String projectId, @PathVariable String chapter,
                                                   @RequestBody ChapterSummary summary) {
        try {
            summary.setChapter(chapter);
            draftStorage.saveChapterSummary(projectId, summary);
            return Map.of("success", true, "message", "Summary saved");
        } catch (IOException e) {
            log.error("保存章节摘要失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
    
    @PutMapping("/{chapter}/summary")
    public Map<String, Object> saveChapterSummary(@PathVariable String projectId, @PathVariable String chapter,
                                                   @RequestBody ChapterSummary summary) {
        try {
            summary.setChapter(chapter);
            draftStorage.saveChapterSummary(projectId, summary);
            return Map.of("success", true, "message", "Chapter summary saved");
        } catch (IOException e) {
            log.error("保存章节摘要失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
    
    // ========== 草稿版本 ==========
    
    @GetMapping("/{chapter}/versions")
    public List<String> listDraftVersions(@PathVariable String projectId, @PathVariable String chapter) {
        return draftStorage.listDraftVersions(projectId, chapter);
    }
    
    @GetMapping("/{chapter}/review")
    public ReviewResult getReview(@PathVariable String projectId, @PathVariable String chapter) {
        return draftStorage.getReview(projectId, chapter)
                .orElseGet(() -> {
                    Draft draft = draftStorage.getLatestDraft(projectId, chapter)
                            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Review not found"));
                    String version = draft.getVersion() != null && !draft.getVersion().isBlank() ? draft.getVersion() : "current";
                    ReviewResult review = editorAgent.reviewDraft(projectId, chapter, version, draft.getContent());
                    try {
                        draftStorage.saveReview(projectId, chapter, review);
                    } catch (IOException e) {
                        log.warn("按需生成 review 后保存失败: {}", e.getMessage());
                    }
                    return review;
                });
    }
    
    // ========== 内容更新 ==========
    
    @PutMapping("/{chapter}/content")
    public Map<String, Object> updateDraftContent(@PathVariable String projectId, @PathVariable String chapter,
                                                   @RequestBody UpdateContentRequest request) {
        try {
            Draft draft = draftStorage.saveCurrentDraft(
                    projectId,
                    chapter,
                    request.content,
                    request.content.length(),
                    null,
                    true
            );
            
            String canonical = draft.getChapter() != null ? draft.getChapter() : chapter;
            
            if (request.title != null) {
                ChapterSummary summary = draftStorage.getChapterSummary(projectId, canonical).orElse(null);
                if (summary != null) {
                    summary.setTitle(request.title);
                    summary.setWordCount(request.content.length());
                } else {
                    summary = ChapterSummary.builder()
                            .chapter(canonical)
                            .title(request.title)
                            .wordCount(request.content.length())
                            .build();
                }
                draftStorage.saveChapterSummary(projectId, summary);
            }
            try {
                bindingsController.rebuildBindingsForChapter(projectId, canonical);
            } catch (Exception e) {
                log.warn("保存后重建绑定失败: {}", e.getMessage());
            }
            orchestratorPool.getOrchestrator(projectId).refreshDraftArtifacts(projectId, canonical);
            
            return Map.of(
                    "success", true,
                    "version", "current",
                    "message", "Content saved",
                    "chapter", canonical,
                    "title", request.title != null ? request.title : ""
            );
        } catch (IOException e) {
            log.error("更新草稿内容失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
    
    @PutMapping("/{chapter}/autosave")
    public Map<String, Object> autosaveDraftContent(@PathVariable String projectId, @PathVariable String chapter,
                                                     @RequestBody UpdateContentRequest request) {
        try {
            Draft draft = draftStorage.saveCurrentDraft(
                    projectId,
                    chapter,
                    request.content,
                    request.content.length(),
                    null,
                    false  // 不创建备份
            );
            
            String canonical = draft.getChapter() != null ? draft.getChapter() : chapter;
            
            if (request.title != null) {
                ChapterSummary summary = draftStorage.getChapterSummary(projectId, canonical).orElse(null);
                if (summary != null) {
                    summary.setTitle(request.title);
                    summary.setWordCount(request.content.length());
                } else {
                    summary = ChapterSummary.builder()
                            .chapter(canonical)
                            .title(request.title)
                            .wordCount(request.content.length())
                            .build();
                }
                draftStorage.saveChapterSummary(projectId, summary);
            }
            orchestratorPool.getOrchestrator(projectId).refreshDraftArtifacts(projectId, canonical);
            
            return Map.of(
                    "success", true,
                    "version", "current",
                    "message", "Content autosaved",
                    "chapter", canonical,
                    "title", request.title != null ? request.title : ""
            );
        } catch (IOException e) {
            log.error("自动保存草稿失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
    
    // ========== 重写和重排 ==========
    
    @PostMapping("/rewrite")
    public Map<String, Object> rewriteText(@PathVariable String projectId,
                                            @RequestBody RewriteTextRequest request) {
        if (request.text == null || request.text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Text cannot be empty");
        }
        try {
            String rewritten = editorAgent.rewriteText(projectId, request.text);
            return Map.of("success", true, "rewritten", rewritten);
        } catch (Exception e) {
            log.error("去 AI 味重写失败", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }
    
    @PostMapping("/reorder")
    public Map<String, Object> reorderChapters(@PathVariable String projectId,
                                                @RequestBody ReorderChaptersRequest request) {
        try {
            List<ChapterSummary> summaries = draftStorage.listChapterSummaries(projectId, request.volumeId);
            // 按 chapterOrder 重新设置 orderIndex
            for (int i = 0; i < request.chapterOrder.size(); i++) {
                String chapterId = request.chapterOrder.get(i);
                for (ChapterSummary s : summaries) {
                    if (chapterId.equals(s.getChapter())) {
                        s.setOrderIndex(i);
                        try {
                            draftStorage.saveChapterSummary(projectId, s);
                        } catch (IOException e) {
                            log.warn("保存章节顺序失败: {}", chapterId);
                        }
                        break;
                    }
                }
            }
            return Map.of("success", true, "updated", request.chapterOrder.size());
        } catch (Exception e) {
            log.error("章节重排失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
    
    // ========== 指定版本 ==========
    
    @GetMapping("/{chapter}/{version}")
    public Draft getDraft(@PathVariable String projectId, @PathVariable String chapter,
                          @PathVariable String version) {
        return draftStorage.getDraft(projectId, chapter, version)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Draft not found"));
    }
    
    // ========== 请求类 ==========
    
    public record DraftSaveRequest(String content, Integer wordCount, List<String> pendingConfirmations) {}
    
    public static class UpdateContentRequest {
        public String content;
        public String title;
    }
    
    public static class RewriteTextRequest {
        public String text;
    }
    
    public static class ReorderChaptersRequest {
        public String volumeId;
        public List<String> chapterOrder;
    }
}
