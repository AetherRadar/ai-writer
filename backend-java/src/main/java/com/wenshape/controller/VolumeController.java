package com.wenshape.controller;

import com.wenshape.model.entity.Volume;
import com.wenshape.model.entity.VolumeSummary;
import com.wenshape.storage.DraftStorage;
import com.wenshape.storage.VolumeStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 分卷管理接口
 */
@Slf4j
@RestController
@RequestMapping("/projects/{projectId}/volumes")
@RequiredArgsConstructor
public class VolumeController {
    
    private final VolumeStorage volumeStorage;
    private final DraftStorage draftStorage;
    
    @GetMapping("")
    public List<Volume> listVolumes(@PathVariable String projectId) {
        return volumeStorage.listVolumes(projectId);
    }
    
    @PostMapping("")
    public Volume createVolume(@PathVariable String projectId, @RequestBody VolumeCreateRequest request) {
        try {
            Volume volume = volumeStorage.createVolume(projectId, request.title(), request.summary(), request.order());
            log.info("Created volume {} for project {}", volume.getId(), projectId);
            return volume;
        } catch (IOException e) {
            log.error("创建分卷失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
    
    @GetMapping("/{volumeId}")
    public Volume getVolume(@PathVariable String projectId, @PathVariable String volumeId) {
        return volumeStorage.getVolume(projectId, volumeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Volume " + volumeId + " not found"));
    }
    
    @PutMapping("/{volumeId}")
    public Volume updateVolume(@PathVariable String projectId, @PathVariable String volumeId,
                               @RequestBody VolumeCreateRequest request) {
        Volume volume = volumeStorage.getVolume(projectId, volumeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Volume " + volumeId + " not found"));
        
        volume.setTitle(request.title());
        volume.setSummary(request.summary());
        if (request.order() != null) {
            volume.setOrder(request.order());
        }
        
        try {
            Volume updated = volumeStorage.updateVolume(projectId, volume);
            log.info("Updated volume {} for project {}", volumeId, projectId);
            return updated;
        } catch (IOException e) {
            log.error("更新分卷失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
    
    @DeleteMapping("/{volumeId}")
    public Map<String, Object> deleteVolume(@PathVariable String projectId, @PathVariable String volumeId) {
        if ("V1".equals(volumeId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Default volume V1 cannot be deleted");
        }
        
        boolean success = volumeStorage.deleteVolume(projectId, volumeId);
        if (!success) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Volume " + volumeId + " not found");
        }
        
        log.info("Deleted volume {} from project {}", volumeId, projectId);
        return Map.of(
                "success", true,
                "message", "Volume " + volumeId + " deleted"
        );
    }

    @GetMapping("/{volumeId}/summary")
    public VolumeSummary getVolumeSummary(@PathVariable String projectId, @PathVariable String volumeId) {
        return volumeStorage.getVolumeSummary(projectId, volumeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Summary for volume " + volumeId + " not found"));
    }

    @PutMapping("/{volumeId}/summary")
    public VolumeSummary saveVolumeSummary(@PathVariable String projectId, @PathVariable String volumeId,
                                           @RequestBody VolumeSummary summary) {
        volumeStorage.getVolume(projectId, volumeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Volume " + volumeId + " not found"));
        summary.setVolumeId(volumeId);
        try {
            return volumeStorage.saveVolumeSummary(projectId, summary);
        } catch (IOException e) {
            log.error("保存分卷摘要失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/{volumeId}/stats")
    public Map<String, Object> getVolumeStats(@PathVariable String projectId, @PathVariable String volumeId) {
        volumeStorage.getVolume(projectId, volumeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Volume " + volumeId + " not found"));

        List<com.wenshape.model.entity.ChapterSummary> summaries = draftStorage.listChapterSummaries(projectId, volumeId);
        int chapterCount = summaries.size();
        int wordCount = summaries.stream().mapToInt(s -> Math.max(0, s.getWordCount())).sum();
        long completedCount = summaries.stream()
                .filter(s -> draftStorage.getFinalDraft(projectId, s.getChapter()).isPresent())
                .count();

        return Map.of(
                "volume_id", volumeId,
                "chapter_count", chapterCount,
                "completed_chapter_count", completedCount,
                "total_word_count", wordCount
        );
    }

    public record VolumeCreateRequest(String title, String summary, Integer order) {}
}
