package com.wenshape.controller;

import com.wenshape.storage.DraftStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 文本分块控制器
 * 对应 Python 后端 /projects/{projectId}/text-chunks/rebuild
 */
@Slf4j
@RestController
@RequestMapping("/projects/{projectId}/text-chunks")
@RequiredArgsConstructor
public class TextChunksController {

    private final DraftStorage draftStorage;

    /**
     * 重建文本分块索引
     * POST /projects/{projectId}/text-chunks/rebuild
     */
    @PostMapping("/rebuild")
    public Map<String, Object> rebuild(@PathVariable String projectId) {
        log.info("Rebuilding text chunks for project: {}", projectId);
        Map<String, Object> meta = draftStorage.rebuildTextChunkIndex(projectId);

        return Map.of(
                "success", true,
                "meta", meta
        );
    }

    @PostMapping("/search")
    public Map<String, Object> search(@PathVariable String projectId,
                                      @RequestBody Map<String, Object> request) {
        String query = (String) request.getOrDefault("query", "");
        int limit = request.get("limit") instanceof Number n ? n.intValue() : 8;
        @SuppressWarnings("unchecked")
        List<String> chapters = (List<String>) request.get("chapters");
        @SuppressWarnings("unchecked")
        List<String> excludeChapters = (List<String>) request.get("exclude_chapters");
        boolean rebuild = Boolean.TRUE.equals(request.get("rebuild"));

        List<Map<String, Object>> results = draftStorage.searchTextChunks(
                projectId,
                query,
                Math.max(1, limit),
                chapters,
                excludeChapters,
                rebuild
        );
        return Map.of("results", results);
    }
}
