package com.wenshape.storage;

import com.wenshape.model.entity.Volume;
import com.wenshape.model.entity.VolumeSummary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 分卷存储
 */
@Slf4j
@Component
public class VolumeStorage extends BaseStorage {
    
    public Volume createVolume(String projectId, String title, String summary, Integer order) throws IOException {
        ensureDefaultVolume(projectId);
        
        List<Volume> volumes = listVolumes(projectId);
        int nextNum = volumes.size() + 1;
        String volumeId = "V" + nextNum;
        
        // 确保 ID 唯一
        while (volumes.stream().anyMatch(v -> v.getId().equals(volumeId))) {
            nextNum++;
        }
        
        Volume volume = Volume.builder()
                .id("V" + nextNum)
                .projectId(projectId)
                .title(title)
                .summary(summary)
                .order(order != null ? order : nextNum)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        
        saveVolume(projectId, volume);
        return volume;
    }
    
    public Optional<Volume> getVolume(String projectId, String volumeId) {
        if ("V1".equals(volumeId)) {
            try {
                ensureDefaultVolume(projectId);
            } catch (IOException e) {
                log.error("确保默认分卷失败", e);
            }
        }
        
        Path filePath = getVolumeFilePath(projectId, volumeId);
        if (!Files.exists(filePath)) {
            return Optional.empty();
        }
        
        try {
            return Optional.of(readYaml(filePath, Volume.class));
        } catch (IOException e) {
            log.error("读取分卷失败: {}", filePath, e);
            return Optional.empty();
        }
    }
    
    public List<Volume> listVolumes(String projectId) {
        try {
            ensureDefaultVolume(projectId);
        } catch (IOException e) {
            log.error("确保默认分卷失败", e);
        }
        
        Path volumesDir = getProjectPath(projectId).resolve("volumes");
        if (!Files.exists(volumesDir)) {
            return new ArrayList<>();
        }
        
        List<Volume> volumes = new ArrayList<>();
        try (Stream<Path> paths = Files.list(volumesDir)) {
            paths.filter(p -> p.toString().endsWith(".yaml"))
                    .filter(p -> !p.getFileName().toString().contains("_summary"))
                    .forEach(p -> {
                        try {
                            volumes.add(readYaml(p, Volume.class));
                        } catch (IOException e) {
                            log.error("读取分卷失败: {}", p, e);
                        }
                    });
        } catch (IOException e) {
            log.error("列出分卷失败: {}", volumesDir, e);
        }
        
        volumes.sort(Comparator.comparingInt(Volume::getOrder));
        return volumes;
    }
    
    public Volume updateVolume(String projectId, Volume volume) throws IOException {
        volume.setUpdatedAt(LocalDateTime.now());
        saveVolume(projectId, volume);
        return volume;
    }
    
    public boolean deleteVolume(String projectId, String volumeId) {
        Path filePath = getVolumeFilePath(projectId, volumeId);
        try {
            return Files.deleteIfExists(filePath);
        } catch (IOException e) {
            log.error("删除分卷失败: {}", filePath, e);
            return false;
        }
    }

    // ========== 分卷摘要 ==========

    public Optional<VolumeSummary> getVolumeSummary(String projectId, String volumeId) {
        Path filePath = getVolumeSummaryFilePath(projectId, volumeId);
        if (!Files.exists(filePath)) {
            return Optional.empty();
        }
        try {
            return Optional.of(readYaml(filePath, VolumeSummary.class));
        } catch (IOException e) {
            log.error("读取分卷摘要失败: {}", filePath, e);
            return Optional.empty();
        }
    }

    public VolumeSummary saveVolumeSummary(String projectId, VolumeSummary summary) throws IOException {
        summary.setUpdatedAt(LocalDateTime.now());
        if (summary.getCreatedAt() == null) summary.setCreatedAt(LocalDateTime.now());
        Path filePath = getVolumeSummaryFilePath(projectId, summary.getVolumeId());
        writeYaml(filePath, summary);
        return summary;
    }

    private Path getVolumeSummaryFilePath(String projectId, String volumeId) {
        return getProjectPath(projectId).resolve("volumes").resolve(volumeId + "_summary.yaml");
    }
    
    private Path getVolumeFilePath(String projectId, String volumeId) {
        return getProjectPath(projectId).resolve("volumes").resolve(volumeId + ".yaml");
    }
    
    private void saveVolume(String projectId, Volume volume) throws IOException {
        Path filePath = getVolumeFilePath(projectId, volume.getId());
        writeYaml(filePath, volume);
    }
    
    private void ensureDefaultVolume(String projectId) throws IOException {
        Path volumesDir = getProjectPath(projectId).resolve("volumes");
        if (Files.exists(volumesDir)) {
            try (Stream<Path> paths = Files.list(volumesDir)) {
                boolean hasVolumes = paths.anyMatch(p -> 
                        p.toString().endsWith(".yaml") && !p.getFileName().toString().contains("_summary"));
                if (hasVolumes) {
                    return;
                }
            }
        }
        
        Volume defaultVolume = Volume.builder()
                .id("V1")
                .projectId(projectId)
                .title("Volume 1")
                .order(1)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        
        saveVolume(projectId, defaultVolume);
    }
}
