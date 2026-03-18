package com.wenshape.controller;

import com.wenshape.model.entity.Project;
import com.wenshape.storage.CardStorage;
import com.wenshape.storage.CanonStorage;
import com.wenshape.storage.DraftStorage;
import com.wenshape.storage.ProjectStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 项目管理接口
 */
@Slf4j
@RestController
@RequestMapping("/projects")
@RequiredArgsConstructor
public class ProjectController {
    
    private final ProjectStorage projectStorage;
    private final CardStorage cardStorage;
    private final CanonStorage canonStorage;
    private final DraftStorage draftStorage;
    
    @GetMapping("")
    public List<Project> listProjects() {
        return projectStorage.listProjects();
    }
    
    @PostMapping("")
    public Project createProject(@RequestBody ProjectCreateRequest request) {
        try {
            return projectStorage.createProject(
                    request.name(),
                    request.description(),
                    request.language()
            );
        } catch (IOException e) {
            log.error("创建项目失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
    
    @GetMapping("/{projectId}")
    public Project getProject(@PathVariable String projectId) {
        return projectStorage.getProject(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    }
    
    @GetMapping("/{projectId}/stats")
    public Map<String, Object> getProjectStats(@PathVariable String projectId) {
        // 统计角色数
        int characterCount = cardStorage.listCharacterCards(projectId).size();
        
        // 统计事实数
        int factCount = canonStorage.listFacts(projectId).size();
        
        // 统计章节数
        List<String> chapters = draftStorage.listChapters(projectId);
        int chapterCount = chapters.size();
        
        // 计算总字数
        int totalWordCount = 0;
        int completedChapters = 0;
        
        for (String chapter : chapters) {
            var draft = draftStorage.getFinalDraft(projectId, chapter);
            if (draft.isPresent()) {
                totalWordCount += draft.get().length();
                completedChapters++;
            }
        }
        
        return Map.of(
                "total_word_count", totalWordCount,
                "completed_chapters", completedChapters,
                "in_progress_chapters", chapterCount - completedChapters,
                "character_count", characterCount,
                "fact_count", factCount
        );
    }
    
    @PatchMapping("/{projectId}")
    public Map<String, Object> renameProject(@PathVariable String projectId, 
                                              @RequestBody ProjectRenameRequest request) {
        try {
            Project project = projectStorage.updateProject(projectId, request.name(), null);
            return Map.of(
                    "success", true,
                    "project", project
            );
        } catch (IOException e) {
            log.error("重命名项目失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
    
    @DeleteMapping("/{projectId}")
    public Map<String, Object> deleteProject(@PathVariable String projectId) {
        boolean success = projectStorage.deleteProject(projectId);
        if (!success) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
        }
        return Map.of(
                "success", true,
                "message", "Project deleted"
        );
    }
    
    public record ProjectCreateRequest(String name, String description, String language) {}
    public record ProjectRenameRequest(String name) {}
}
