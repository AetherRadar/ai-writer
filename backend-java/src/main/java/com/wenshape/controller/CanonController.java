package com.wenshape.controller;

import com.wenshape.model.entity.Fact;
import com.wenshape.storage.CanonStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 事实表管理接口
 */
@Slf4j
@RestController
@RequestMapping("/projects/{projectId}/canon")
@RequiredArgsConstructor
public class CanonController {
    
    private final CanonStorage canonStorage;
    
    @GetMapping("/facts")
    public List<Fact> listFacts(@PathVariable String projectId) {
        return canonStorage.listFacts(projectId);
    }
    
    @GetMapping("/facts/{factId}")
    public Fact getFact(@PathVariable String projectId, @PathVariable String factId) {
        return canonStorage.getFact(projectId, factId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Fact not found"));
    }

    @GetMapping("/facts/by-id/{factId}")
    public Fact getFactById(@PathVariable String projectId, @PathVariable String factId) {
        return getFact(projectId, factId);
    }
    
    @PostMapping("/facts")
    public Fact createFact(@PathVariable String projectId, @RequestBody Fact fact) {
        try {
            return canonStorage.saveFact(projectId, fact);
        } catch (IOException e) {
            log.error("创建事实失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    // 前端使用 /canon/facts/manual 路径
    @PostMapping("/facts/manual")
    public Fact createFactManual(@PathVariable String projectId, @RequestBody Fact fact) {
        try {
            return canonStorage.saveFact(projectId, fact);
        } catch (IOException e) {
            log.error("创建事实失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
    
    @PutMapping("/facts/{factId}")
    public Fact updateFact(@PathVariable String projectId, @PathVariable String factId, @RequestBody Fact fact) {
        if (!canonStorage.getFact(projectId, factId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Fact not found");
        }
        
        try {
            fact.setId(factId);
            return canonStorage.saveFact(projectId, fact);
        } catch (IOException e) {
            log.error("更新事实失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    // 前端使用 /canon/facts/by-id/{factId} 路径
    @PutMapping("/facts/by-id/{factId}")
    public Fact updateFactById(@PathVariable String projectId, @PathVariable String factId, @RequestBody Fact fact) {
        return updateFact(projectId, factId, fact);
    }
    
    @DeleteMapping("/facts/{factId}")
    public Map<String, Object> deleteFact(@PathVariable String projectId, @PathVariable String factId) {
        boolean success = canonStorage.deleteFact(projectId, factId);
        if (!success) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Fact not found");
        }
        return Map.of("success", true, "message", "Fact deleted");
    }

    // 前端使用 /canon/facts/by-id/{factId} 路径
    @DeleteMapping("/facts/by-id/{factId}")
    public Map<String, Object> deleteFactById(@PathVariable String projectId, @PathVariable String factId) {
        return deleteFact(projectId, factId);
    }

    @GetMapping(value = "/facts", params = "chapter")
    public List<Fact> listFactsByChapter(@PathVariable String projectId, @RequestParam String chapter) {
        return canonStorage.listFacts(projectId).stream()
                .filter(fact -> chapter.equals(fact.getIntroducedIn()))
                .toList();
    }

    @GetMapping("/timeline")
    public List<Map<String, Object>> listTimeline(@PathVariable String projectId,
                                                  @RequestParam(required = false) String chapter) {
        if (chapter != null && !chapter.isBlank()) {
            return canonStorage.listTimelineEventsByChapter(projectId, chapter);
        }
        return canonStorage.listTimelineEvents(projectId);
    }

    @GetMapping("/timeline/{chapter}")
    public List<Map<String, Object>> listTimelineByChapter(@PathVariable String projectId,
                                                           @PathVariable String chapter) {
        return canonStorage.listTimelineEventsByChapter(projectId, chapter);
    }

    @PostMapping("/timeline")
    public Map<String, Object> createTimelineEvent(@PathVariable String projectId,
                                                   @RequestBody Map<String, Object> event) {
        try {
            canonStorage.saveTimelineEvent(projectId, event);
            return Map.of("success", true, "message", "Timeline event added");
        } catch (IOException e) {
            log.error("保存时间线事件失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/character-state")
    public List<Map<String, Object>> listCharacterState(@PathVariable String projectId) {
        return canonStorage.listCharacterStates(projectId);
    }

    @GetMapping("/character-state/{characterName}")
    public Map<String, Object> getCharacterState(@PathVariable String projectId,
                                                 @PathVariable String characterName) {
        return canonStorage.getCharacterState(projectId, characterName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Character state not found"));
    }

    @PutMapping("/character-state/{characterName}")
    public Map<String, Object> saveCharacterState(@PathVariable String projectId,
                                                  @PathVariable String characterName,
                                                  @RequestBody Map<String, Object> state) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>(state);
        payload.putIfAbsent("character", characterName);
        try {
            canonStorage.saveCharacterState(projectId, payload);
            return payload;
        } catch (IOException e) {
            log.error("保存角色状态失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/character-state")
    public Map<String, Object> saveCharacterState(@PathVariable String projectId,
                                                  @RequestBody Map<String, Object> state) {
        String characterName = String.valueOf(state.getOrDefault("character", state.getOrDefault("name", "")));
        if (characterName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "character is required");
        }
        return saveCharacterState(projectId, characterName, state);
    }
}
