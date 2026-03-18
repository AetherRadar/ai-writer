package com.wenshape.storage;

import com.wenshape.model.entity.Project;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 项目存储
 */
@Slf4j
@Component
public class ProjectStorage extends BaseStorage {
    
    public Project createProject(String name, String description, String language) throws IOException {
        String projectId = sanitizeProjectId(name);
        Path projectPath = getProjectPath(projectId);
        
        if (Files.exists(projectPath)) {
            throw new IOException("项目已存在: " + projectId);
        }
        
        Project project = Project.builder()
                .id(projectId)
                .name(name)
                .description(description)
                .language(language != null ? language : "zh")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        
        // 创建项目目录结构
        ensureDir(projectPath);
        ensureDir(projectPath.resolve("cards/characters"));
        ensureDir(projectPath.resolve("cards/world"));
        ensureDir(projectPath.resolve("drafts"));
        ensureDir(projectPath.resolve("summaries"));
        ensureDir(projectPath.resolve("volumes"));
        ensureDir(projectPath.resolve("canon"));
        
        // 保存项目元数据
        saveProjectMeta(projectId, project);
        
        return project;
    }
    
    public Optional<Project> getProject(String projectId) {
        Path projectPath = getProjectPath(projectId);
        if (!Files.exists(projectPath) || !Files.isDirectory(projectPath)) {
            return Optional.empty();
        }
        
        Path metaPath = projectPath.resolve("project.yaml");
        if (Files.exists(metaPath)) {
            try {
                Project project = readYaml(metaPath, Project.class);
                return Optional.of(ensureProjectId(projectId, project));
            } catch (Exception e) {
                log.error("读取项目元数据失败: {} - {}", metaPath, e.getMessage(), e);
            }
        }
        
        // 如果没有 project.yaml，从目录名推断
        return Optional.of(Project.builder()
                .id(projectId)
                .name(projectId)
                .language("zh")
                .createdAt(getCreationTime(projectPath))
                .updatedAt(getModificationTime(projectPath))
                .build());
    }
    
    private Project ensureProjectId(String projectId, Project project) {
        if (project.getId() == null || project.getId().isEmpty()) {
            project.setId(projectId);
        }
        return project;
    }
    
    public List<Project> listProjects() {
        Path dataPath = getDataPath().toAbsolutePath().normalize();
        log.info("listProjects: dataPath={}, exists={}", dataPath, Files.exists(dataPath));
        if (!Files.exists(dataPath)) {
            log.warn("数据目录不存在: {}", dataPath);
            return new ArrayList<>();
        }
        
        List<Project> projects = new ArrayList<>();
        try (Stream<Path> paths = Files.list(dataPath)) {
            paths.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .forEach(p -> {
                        String projectId = p.getFileName().toString();
                        try {
                            getProject(projectId).ifPresent(projects::add);
                        } catch (Exception e) {
                            log.error("加载项目失败: {}", projectId, e);
                        }
                    });
        } catch (IOException e) {
            log.error("列出项目失败", e);
        }
        
        log.info("listProjects: found {} projects", projects.size());
        return projects;
    }
    
    public Project updateProject(String projectId, String name, String description) throws IOException {
        Project project = getProject(projectId)
                .orElseThrow(() -> new IOException("项目不存在: " + projectId));
        
        if (name != null) project.setName(name);
        if (description != null) project.setDescription(description);
        project.setUpdatedAt(LocalDateTime.now());
        
        saveProjectMeta(projectId, project);
        return project;
    }
    
    public boolean deleteProject(String projectId) {
        Path projectPath = getProjectPath(projectId);
        if (!Files.exists(projectPath)) {
            return false;
        }
        
        try {
            deleteDirectory(projectPath);
            return true;
        } catch (IOException e) {
            log.error("删除项目失败: {}", projectPath, e);
            return false;
        }
    }
    
    private void saveProjectMeta(String projectId, Project project) throws IOException {
        Path metaPath = getProjectPath(projectId).resolve("project.yaml");
        writeYaml(metaPath, project);
    }
    
    private String sanitizeProjectId(String name) {
        // 保留中文字符，移除特殊字符
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }
    
    private LocalDateTime getCreationTime(Path path) {
        try {
            return LocalDateTime.ofInstant(
                    Files.getLastModifiedTime(path).toInstant(),
                    java.time.ZoneId.systemDefault());
        } catch (IOException e) {
            return LocalDateTime.now();
        }
    }
    
    private LocalDateTime getModificationTime(Path path) {
        try {
            return LocalDateTime.ofInstant(
                    Files.getLastModifiedTime(path).toInstant(),
                    java.time.ZoneId.systemDefault());
        } catch (IOException e) {
            return LocalDateTime.now();
        }
    }
    
    private void deleteDirectory(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (Stream<Path> entries = Files.list(path)) {
                for (Path entry : entries.toList()) {
                    deleteDirectory(entry);
                }
            }
        }
        Files.delete(path);
    }
}
