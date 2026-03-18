package com.wenshape.storage;

import com.wenshape.model.entity.CharacterCard;
import com.wenshape.model.entity.StyleCard;
import com.wenshape.model.entity.WorldCard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 卡片存储 - 角色卡、世界观卡、文风卡
 */
@Slf4j
@Component
public class CardStorage extends BaseStorage {
    
    // ========== 角色卡 ==========
    
    public Optional<CharacterCard> getCharacterCard(String projectId, String characterName) {
        Path filePath = getProjectPath(projectId)
                .resolve("cards/characters")
                .resolve(characterName + ".yaml");
        
        if (!Files.exists(filePath)) {
            return Optional.empty();
        }
        
        try {
            Map<String, Object> data = readYamlAsMap(filePath);
            return Optional.of(coerceCharacterData(data));
        } catch (IOException e) {
            log.error("读取角色卡失败: {}", filePath, e);
            return Optional.empty();
        }
    }
    
    public void saveCharacterCard(String projectId, CharacterCard card) throws IOException {
        Path filePath = getProjectPath(projectId)
                .resolve("cards/characters")
                .resolve(card.getName() + ".yaml");
        writeYaml(filePath, card);
    }
    
    public List<String> listCharacterCards(String projectId) {
        Path cardsDir = getProjectPath(projectId).resolve("cards/characters");
        if (!Files.exists(cardsDir)) {
            return new ArrayList<>();
        }
        
        try (Stream<Path> paths = Files.list(cardsDir)) {
            return paths
                    .filter(p -> p.toString().endsWith(".yaml"))
                    .map(p -> p.getFileName().toString().replace(".yaml", ""))
                    .toList();
        } catch (IOException e) {
            log.error("列出角色卡失败: {}", cardsDir, e);
            return new ArrayList<>();
        }
    }
    
    public boolean deleteCharacterCard(String projectId, String characterName) {
        Path filePath = getProjectPath(projectId)
                .resolve("cards/characters")
                .resolve(characterName + ".yaml");
        try {
            return Files.deleteIfExists(filePath);
        } catch (IOException e) {
            log.error("删除角色卡失败: {}", filePath, e);
            return false;
        }
    }
    
    // ========== 世界观卡 ==========
    
    public Optional<WorldCard> getWorldCard(String projectId, String cardName) {
        Path filePath = getProjectPath(projectId)
                .resolve("cards/world")
                .resolve(cardName + ".yaml");
        
        if (!Files.exists(filePath)) {
            return Optional.empty();
        }
        
        try {
            Map<String, Object> data = readYamlAsMap(filePath);
            return Optional.of(coerceWorldData(data));
        } catch (IOException e) {
            log.error("读取世界观卡失败: {}", filePath, e);
            return Optional.empty();
        }
    }
    
    public void saveWorldCard(String projectId, WorldCard card) throws IOException {
        Path filePath = getProjectPath(projectId)
                .resolve("cards/world")
                .resolve(card.getName() + ".yaml");
        writeYaml(filePath, card);
    }
    
    public List<String> listWorldCards(String projectId) {
        Path cardsDir = getProjectPath(projectId).resolve("cards/world");
        if (!Files.exists(cardsDir)) {
            return new ArrayList<>();
        }
        
        try (Stream<Path> paths = Files.list(cardsDir)) {
            return paths
                    .filter(p -> p.toString().endsWith(".yaml"))
                    .map(p -> p.getFileName().toString().replace(".yaml", ""))
                    .toList();
        } catch (IOException e) {
            log.error("列出世界观卡失败: {}", cardsDir, e);
            return new ArrayList<>();
        }
    }
    
    public boolean deleteWorldCard(String projectId, String cardName) {
        Path filePath = getProjectPath(projectId)
                .resolve("cards/world")
                .resolve(cardName + ".yaml");
        try {
            return Files.deleteIfExists(filePath);
        } catch (IOException e) {
            log.error("删除世界观卡失败: {}", filePath, e);
            return false;
        }
    }
    
    // ========== 文风卡 ==========
    
    public Optional<StyleCard> getStyleCard(String projectId) {
        Path filePath = getProjectPath(projectId).resolve("cards/style.yaml");
        
        if (!Files.exists(filePath)) {
            return Optional.empty();
        }
        
        try {
            Map<String, Object> data = readYamlAsMap(filePath);
            return Optional.of(coerceStyleData(data));
        } catch (IOException e) {
            log.error("读取文风卡失败: {}", filePath, e);
            return Optional.empty();
        }
    }
    
    public void saveStyleCard(String projectId, StyleCard card) throws IOException {
        Path filePath = getProjectPath(projectId).resolve("cards/style.yaml");
        writeYaml(filePath, card);
    }
    
    // ========== 数据转换 ==========
    
    @SuppressWarnings("unchecked")
    private CharacterCard coerceCharacterData(Map<String, Object> data) {
        String name = getString(data, "name");
        List<String> aliases = normalizeAliases(data.get("aliases"));
        Integer stars = normalizeStars(data.get("stars"));
        String description = getString(data, "description");
        
        if (description == null || description.isBlank()) {
            // 从其他字段组装 description
            StringBuilder sb = new StringBuilder();
            appendIfPresent(sb, "身份", getString(data, "identity"));
            appendIfPresent(sb, "外貌", getString(data, "appearance"));
            appendIfPresent(sb, "动机", getString(data, "motivation"));
            
            Object personality = data.get("personality");
            if (personality instanceof List<?> list && !list.isEmpty()) {
                sb.append("性格: ").append(String.join(", ", list.stream().map(Object::toString).toList())).append("\n");
            }
            
            appendIfPresent(sb, "口吻", getString(data, "speech_pattern"));
            appendIfPresent(sb, "角色弧线", getString(data, "arc"));
            
            description = sb.toString().trim();
        }
        
        return CharacterCard.builder()
                .name(name)
                .aliases(aliases)
                .description(description)
                .stars(stars)
                .build();
    }
    
    @SuppressWarnings("unchecked")
    private WorldCard coerceWorldData(Map<String, Object> data) {
        String name = getString(data, "name");
        String description = getString(data, "description");
        List<String> aliases = normalizeAliases(data.get("aliases"));
        Integer stars = normalizeStars(data.get("stars"));
        String category = getString(data, "category");
        
        List<String> rules = new ArrayList<>();
        Object rulesObj = data.get("rules");
        if (rulesObj instanceof List<?> list) {
            rules = list.stream().map(Object::toString).filter(s -> !s.isBlank()).toList();
        } else if (rulesObj instanceof String str) {
            rules = List.of(str.split("[,\n，;；]+"));
        }
        
        Boolean immutable = data.get("immutable") instanceof Boolean b ? b : null;
        
        return WorldCard.builder()
                .name(name)
                .description(description)
                .aliases(aliases)
                .category(category)
                .rules(new ArrayList<>(rules))
                .immutable(immutable)
                .stars(stars)
                .build();
    }
    
    private StyleCard coerceStyleData(Map<String, Object> data) {
        String style = getString(data, "style");
        if (style == null || style.isBlank()) {
            style = getString(data, "content");
        }
        return StyleCard.builder().style(style).build();
    }
    
    private String getString(Map<String, Object> data, String key) {
        Object val = data.get(key);
        return val != null ? val.toString().trim() : "";
    }
    
    @SuppressWarnings("unchecked")
    private List<String> normalizeAliases(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof String str) {
            for (String part : str.split("[,，;；\n]+")) {
                if (!part.isBlank()) result.add(part.trim());
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                String s = item.toString().trim();
                if (!s.isBlank()) result.add(s);
            }
        }
        return result;
    }
    
    private Integer normalizeStars(Object value) {
        try {
            int stars = Integer.parseInt(value.toString());
            return Math.max(1, Math.min(stars, 3));
        } catch (Exception e) {
            return 1;
        }
    }
    
    private void appendIfPresent(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(label).append(": ").append(value).append("\n");
        }
    }
}
