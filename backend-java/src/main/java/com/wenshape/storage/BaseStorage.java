package com.wenshape.storage;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * 存储基类 - 提供 YAML/JSON/文本文件的读写操作
 */
@Slf4j
public abstract class BaseStorage {
    
    protected final ObjectMapper yamlMapper;
    protected final ObjectMapper jsonMapper;
    
    @Value("${wenshape.data-dir:../data}")
    protected String dataDir;
    
    /**
     * 兼容 Python 日期格式的反序列化器
     * Python 写入格式: "2026-03-08 15:32:13.527715"
     * Java ISO 格式:   "2026-03-08T15:32:13.527715"
     */
    private static class FlexibleLocalDateTimeDeserializer extends StdDeserializer<LocalDateTime> {
        private static final DateTimeFormatter[] FORMATTERS = {
                DateTimeFormatter.ISO_LOCAL_DATE_TIME,                          // 2026-03-08T15:32:13
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS"),     // Python 微秒
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),        // 毫秒
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),            // 无小数
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS"),   // ISO 微秒
        };
        
        public FlexibleLocalDateTimeDeserializer() {
            super(LocalDateTime.class);
        }
        
        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String text = p.getText();
            if (text == null || text.isBlank()) {
                return null;
            }
            text = text.trim();
            
            for (DateTimeFormatter fmt : FORMATTERS) {
                try {
                    return LocalDateTime.parse(text, fmt);
                } catch (DateTimeParseException ignored) {
                }
            }
            
            // 最后尝试：把空格替换成 T 再解析
            try {
                return LocalDateTime.parse(text.replace(' ', 'T'));
            } catch (DateTimeParseException e) {
                log.warn("无法解析日期: '{}', 返回 null", text);
                return null;
            }
        }
    }
    
    public BaseStorage() {
        // 自定义日期模块（覆盖 JavaTimeModule 的默认 LocalDateTime 反序列化器）
        SimpleModule flexDateModule = new SimpleModule("FlexibleDateModule");
        flexDateModule.addDeserializer(LocalDateTime.class, new FlexibleLocalDateTimeDeserializer());
        
        // YAML mapper
        YAMLFactory yamlFactory = YAMLFactory.builder()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .build();
        this.yamlMapper = new ObjectMapper(yamlFactory);
        this.yamlMapper.registerModule(new JavaTimeModule());
        this.yamlMapper.registerModule(flexDateModule);  // 覆盖默认反序列化器
        this.yamlMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.yamlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        
        // JSON mapper
        this.jsonMapper = new ObjectMapper();
        this.jsonMapper.registerModule(new JavaTimeModule());
        this.jsonMapper.registerModule(flexDateModule);
        this.jsonMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.jsonMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
    
    public Path getDataPath() {
        Path p = Path.of(dataDir).toAbsolutePath().normalize();
        if (Files.exists(p)) {
            return p;
        }
        // 备用：尝试 ../data（从 backend-java/ 目录启动时）
        Path alt1 = Path.of("../data").toAbsolutePath().normalize();
        if (Files.exists(alt1)) {
            log.debug("dataDir {} 不存在，使用备用路径 {}", p, alt1);
            return alt1;
        }
        // 备用：尝试 ./data（从项目根目录启动时）
        Path alt2 = Path.of("data").toAbsolutePath().normalize();
        if (Files.exists(alt2)) {
            log.debug("dataDir {} 不存在，使用备用路径 {}", p, alt2);
            return alt2;
        }
        log.warn("数据目录不存在: {} (工作目录: {})", p, Path.of("").toAbsolutePath());
        return p;
    }
    
    public Path getProjectPath(String projectId) {
        return getDataPath().resolve(projectId);
    }
    
    protected void ensureDir(Path path) throws IOException {
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }
    }
    
    /**
     * 原子化写入文件（先写临时文件再 rename）
     */
    protected void atomicWrite(Path filePath, String content) throws IOException {
        ensureDir(filePath.getParent());
        Path tmpPath = filePath.resolveSibling(filePath.getFileName() + ".tmp");
        try {
            Files.writeString(tmpPath, content, StandardCharsets.UTF_8);
            Files.move(tmpPath, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmpPath);
        }
    }
    
    /**
     * 读取 YAML 文件
     */
    protected <T> T readYaml(Path filePath, Class<T> clazz) throws IOException {
        if (!Files.exists(filePath)) {
            throw new IOException("File not found: " + filePath);
        }
        String content = Files.readString(filePath, StandardCharsets.UTF_8);
        return yamlMapper.readValue(content, clazz);
    }
    
    /**
     * 读取 YAML 文件为 Map
     */
    public Map<String, Object> readYamlAsMap(Path filePath) throws IOException {
        if (!Files.exists(filePath)) {
            throw new IOException("File not found: " + filePath);
        }
        String content = Files.readString(filePath, StandardCharsets.UTF_8);
        return yamlMapper.readValue(content, new TypeReference<>() {});
    }
    
    /**
     * 写入 YAML 文件
     */
    public void writeYaml(Path filePath, Object data) throws IOException {
        String content = yamlMapper.writeValueAsString(data);
        atomicWrite(filePath, content);
    }
    
    /**
     * 读取文本文件
     */
    protected String readText(Path filePath) throws IOException {
        if (!Files.exists(filePath)) {
            throw new IOException("File not found: " + filePath);
        }
        return Files.readString(filePath, StandardCharsets.UTF_8);
    }
    
    /**
     * 写入文本文件
     */
    protected void writeText(Path filePath, String content) throws IOException {
        atomicWrite(filePath, content);
    }
}
