package com.wenshape.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenshape.llm.LlmGateway;
import com.wenshape.model.entity.CharacterCard;
import com.wenshape.model.entity.StyleCard;
import com.wenshape.model.entity.WorldCard;
import com.wenshape.storage.CardStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 卡片管理接口（角色卡、世界观卡、文风卡）
 */
@Slf4j
@RestController
@RequestMapping("/projects/{projectId}/cards")
@RequiredArgsConstructor
public class CardController {

    private final CardStorage cardStorage;
    private final LlmGateway llmGateway;

    // ========== 角色卡 ==========

    @GetMapping("/characters")
    public List<String> listCharacterCards(@PathVariable String projectId) {
        return cardStorage.listCharacterCards(projectId);
    }

    @GetMapping("/characters/index")
    public List<CharacterCard> listCharacterCardsIndex(@PathVariable String projectId) {
        return cardStorage.listCharacterCards(projectId).stream()
                .map(name -> cardStorage.getCharacterCard(projectId, name))
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .toList();
    }

    @GetMapping("/characters/{characterName}")
    public CharacterCard getCharacterCard(@PathVariable String projectId, @PathVariable String characterName) {
        return cardStorage.getCharacterCard(projectId, characterName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Character card not found"));
    }

    @PostMapping("/characters")
    public Map<String, Object> createCharacterCard(@PathVariable String projectId, @RequestBody CharacterCard card) {
        try {
            cardStorage.saveCharacterCard(projectId, card);
            return Map.of("success", true, "message", "Character card created");
        } catch (IOException e) {
            log.error("创建角色卡失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PutMapping("/characters/{characterName}")
    public Map<String, Object> updateCharacterCard(@PathVariable String projectId,
                                                    @PathVariable String characterName,
                                                    @RequestBody CharacterCard card) {
        try {
            card.setName(characterName);
            cardStorage.saveCharacterCard(projectId, card);
            return Map.of("success", true, "message", "Character card updated");
        } catch (IOException e) {
            log.error("更新角色卡失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @DeleteMapping("/characters/{characterName}")
    public Map<String, Object> deleteCharacterCard(@PathVariable String projectId, @PathVariable String characterName) {
        if (!cardStorage.deleteCharacterCard(projectId, characterName)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Character card not found");
        }
        return Map.of("success", true, "message", "Character card deleted");
    }

    // ========== 世界观卡 ==========

    @GetMapping("/world")
    public List<String> listWorldCards(@PathVariable String projectId) {
        return cardStorage.listWorldCards(projectId);
    }

    @GetMapping("/world/index")
    public List<WorldCard> listWorldCardsIndex(@PathVariable String projectId) {
        return cardStorage.listWorldCards(projectId).stream()
                .map(name -> cardStorage.getWorldCard(projectId, name))
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .toList();
    }

    @GetMapping("/world/{cardName}")
    public WorldCard getWorldCard(@PathVariable String projectId, @PathVariable String cardName) {
        return cardStorage.getWorldCard(projectId, cardName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "World card not found"));
    }

    @PostMapping("/world")
    public Map<String, Object> createWorldCard(@PathVariable String projectId, @RequestBody WorldCard card) {
        try {
            cardStorage.saveWorldCard(projectId, card);
            return Map.of("success", true, "message", "World card created");
        } catch (IOException e) {
            log.error("创建世界观卡失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PutMapping("/world/{cardName}")
    public Map<String, Object> updateWorldCard(@PathVariable String projectId,
                                                @PathVariable String cardName,
                                                @RequestBody WorldCard card) {
        try {
            card.setName(cardName);
            cardStorage.saveWorldCard(projectId, card);
            return Map.of("success", true, "message", "World card updated");
        } catch (IOException e) {
            log.error("更新世界观卡失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @DeleteMapping("/world/{cardName}")
    public Map<String, Object> deleteWorldCard(@PathVariable String projectId, @PathVariable String cardName) {
        if (!cardStorage.deleteWorldCard(projectId, cardName)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "World card not found");
        }
        return Map.of("success", true, "message", "World card deleted");
    }

    // ========== 文风卡 ==========

    @GetMapping("/style")
    public StyleCard getStyleCard(@PathVariable String projectId) {
        return cardStorage.getStyleCard(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Style card not found"));
    }

    @PutMapping("/style")
    public Map<String, Object> updateStyleCard(@PathVariable String projectId, @RequestBody StyleCard card) {
        try {
            cardStorage.saveStyleCard(projectId, card);
            return Map.of("success", true, "message", "Style card updated");
        } catch (IOException e) {
            log.error("更新文风卡失败", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    // ========== LLM 接口 ==========

    /**
     * 从文本提取文风
     * POST /projects/{projectId}/cards/style/extract
     */
    @PostMapping("/style/extract")
    public Map<String, Object> extractStyle(@PathVariable String projectId,
                                             @RequestBody Map<String, Object> body) {
        String content = (String) body.getOrDefault("content", "");
        if (content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content is required");
        }
        String text = content.length() > 6000 ? content.substring(0, 6000) + "..." : content;
        String systemPrompt = "你是一位专业的文学风格分析师，擅长从文本中提炼写作风格特征。";
        String userPrompt = "请分析以下文本的写作风格，提炼出简洁的文风描述（200字以内）：\n\n"
                + text
                + "\n\n请从以下维度描述：叙事视角、句式特点、用词风格、情感基调、节奏感。\n直接输出文风描述，不要加标题或前缀。";

        var resp = llmGateway.chat(
                List.of(Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)),
                llmGateway.getProviderForAgent("extractor"), null, 1000);
        return Map.of("style", resp.getContent().trim());
    }

    /**
     * AI 生成卡片描述
     * POST /projects/{projectId}/cards/generate
     */
    @PostMapping("/generate")
    public Map<String, Object> generateCardDescription(@PathVariable String projectId,
                                                        @RequestBody Map<String, Object> body) {
        String cardType = (String) body.getOrDefault("card_type", "character");
        String name = (String) body.getOrDefault("name", "");
        String styleHint = (String) body.getOrDefault("style_hint", "");
        String note = (String) body.getOrDefault("note", "");
        if (name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        String typeLabel = switch (cardType) {
            case "world" -> "世界观/地点/组织";
            case "style" -> "文风";
            default -> "角色";
        };
        String systemPrompt = "你是一位专业的小说创作助手，擅长为小说角色和世界观生成详细描述。";
        String userPrompt = "请为以下" + typeLabel + "生成一段详细描述（150-300字）：\n\n名称：" + name + "\n"
                + (styleHint.isBlank() ? "" : "风格提示：" + styleHint + "\n")
                + (note.isBlank() ? "" : "补充说明：" + note + "\n")
                + "\n要求：描述具体生动，适合作为小说创作参考。直接输出描述内容，不要加标题。";

        var resp = llmGateway.chat(
                List.of(Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)),
                llmGateway.getProviderForAgent("extractor"), null, 1500);
        return Map.of("description", resp.getContent().trim());
    }

    /**
     * 从大纲文本提取卡片
     * POST /projects/{projectId}/cards/extract-from-outline
     */
    @PostMapping("/extract-from-outline")
    public Map<String, Object> extractFromOutline(@PathVariable String projectId,
                                                   @RequestBody Map<String, Object> body) {
        String outlineText = (String) body.getOrDefault("outline_text", "");
        if (outlineText.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "outline_text is required");
        }
        String text = outlineText.length() > 8000 ? outlineText.substring(0, 8000) + "..." : outlineText;
        String systemPrompt = "你是一位专业的小说策划助手，擅长从大纲中提取角色和世界观信息。";
        String userPrompt = "请从以下小说大纲中提取角色和世界观卡片信息，以 JSON 格式输出：\n\n"
                + text
                + "\n\n输出格式（JSON 数组）：\n"
                + "[\n"
                + "  {\"type\": \"character\", \"name\": \"角色名\", \"description\": \"简要描述\"},\n"
                + "  {\"type\": \"world\", \"name\": \"地点/组织名\", \"description\": \"简要描述\", \"category\": \"location/organization/item/concept\"}\n"
                + "]\n\n只输出 JSON，不要其他内容。";

        var resp = llmGateway.chat(
                List.of(Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)),
                llmGateway.getProviderForAgent("extractor"), null, 2000);

        String raw = resp.getContent().trim();
        if (raw.startsWith("```")) {
            raw = raw.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").trim();
        }
        try {
            List<?> cards = new ObjectMapper().readValue(raw, List.class);
            return Map.of("cards", cards);
        } catch (Exception e) {
            log.warn("解析卡片 JSON 失败: {}", e.getMessage());
            return Map.of("cards", List.of(), "raw", raw);
        }
    }
}
