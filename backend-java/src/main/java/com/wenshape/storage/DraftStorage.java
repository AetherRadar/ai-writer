package com.wenshape.storage;

import com.wenshape.model.entity.ChapterSummary;
import com.wenshape.model.entity.Draft;
import com.wenshape.model.entity.ReviewResult;
import com.wenshape.model.entity.SceneBrief;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 草稿存储
 */
@Slf4j
@Component
public class DraftStorage extends BaseStorage {
    
    private static final Pattern CHAPTER_ID_PATTERN = Pattern.compile("V(\\d+)C(\\d+)");
    private static final int MAX_DRAFT_PREV_BACKUPS = 3;
    
    // ========== 草稿操作 ==========
    
    public Draft saveCurrentDraft(String projectId, String chapter, String content, 
                                   Integer wordCount, List<String> pendingConfirmations,
                                   boolean createPrevBackup) throws IOException {
        String canonical = canonicalizeChapterId(chapter);
        Path finalPath = getFinalPath(projectId, canonical);
        Path historyDir = finalPath.getParent().resolve("history");
        
        int wc = wordCount != null ? wordCount : content.length();
        
        // 备份旧版本
        if (createPrevBackup && Files.exists(finalPath)) {
            rotateDraftHistory(finalPath, historyDir);
        }
        
        // 写入新内容
        writeText(finalPath, content);
        
        // 保存元数据
        Draft draft = Draft.builder()
                .chapter(canonical)
                .version("current")
                .content(content)
                .wordCount(wc)
                .pendingConfirmations(pendingConfirmations != null ? pendingConfirmations : new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .build();
        
        Path metaPath = finalPath.resolveSibling("final.meta.yaml");
        writeYaml(metaPath, draft);
        
        return draft;
    }
    
    public Optional<Draft> getLatestDraft(String projectId, String chapter) {
        String resolved = resolveChapterDirName(projectId, chapter);
        Path draftDir = getProjectPath(projectId).resolve("drafts").resolve(resolved);
        
        if (!Files.exists(draftDir)) {
            return Optional.empty();
        }
        
        // 优先读取 final.md
        Path finalPath = draftDir.resolve("final.md");
        if (Files.exists(finalPath)) {
            return readDraftFromFile(finalPath, chapter, "current");
        }
        
        // 回退到 draft_*.md
        try (Stream<Path> paths = Files.list(draftDir)) {
            Optional<Path> latestDraft = paths
                    .filter(p -> p.getFileName().toString().matches("draft_.*\\.md"))
                    .max(Comparator.comparingLong(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            return 0;
                        }
                    }));
            
            if (latestDraft.isPresent()) {
                String version = latestDraft.get().getFileName().toString()
                        .replace("draft_", "").replace(".md", "");
                return readDraftFromFile(latestDraft.get(), chapter, version);
            }
        } catch (IOException e) {
            log.error("读取草稿失败: {}", draftDir, e);
        }
        
        return Optional.empty();
    }
    
    public Optional<String> getFinalDraft(String projectId, String chapter) {
        String resolved = resolveChapterDirName(projectId, chapter);
        Path finalPath = getProjectPath(projectId).resolve("drafts").resolve(resolved).resolve("final.md");
        
        if (Files.exists(finalPath)) {
            try {
                return Optional.of(readText(finalPath));
            } catch (IOException e) {
                log.error("读取最终稿失败: {}", finalPath, e);
            }
        }
        
        return Optional.empty();
    }
    
    public List<String> listChapters(String projectId) {
        Path draftsDir = getProjectPath(projectId).resolve("drafts");
        if (!Files.exists(draftsDir)) {
            return new ArrayList<>();
        }
        
        Set<String> seen = new HashSet<>();
        List<String> chapters = new ArrayList<>();
        
        try (Stream<Path> paths = Files.list(draftsDir)) {
            paths.filter(Files::isDirectory)
                    .forEach(p -> {
                        String canonical = canonicalizeChapterId(p.getFileName().toString());
                        if (!canonical.isEmpty() && !seen.contains(canonical)) {
                            seen.add(canonical);
                            chapters.add(canonical);
                        }
                    });
        } catch (IOException e) {
            log.error("列出章节失败: {}", draftsDir, e);
        }
        
        // 按章节 ID 排序
        chapters.sort(this::compareChapterIds);
        return chapters;
    }
    
    public boolean deleteChapter(String projectId, String chapter) throws IOException {
        String canonical = canonicalizeChapterId(chapter);
        boolean deleted = false;
        
        // 删除草稿目录
        Path draftsDir = getProjectPath(projectId).resolve("drafts");
        if (Files.exists(draftsDir)) {
            try (Stream<Path> paths = Files.list(draftsDir)) {
                for (Path p : paths.filter(Files::isDirectory).toList()) {
                    if (canonicalizeChapterId(p.getFileName().toString()).equals(canonical)) {
                        deleteDirectory(p);
                        deleted = true;
                    }
                }
            }
        }
        
        // 删除摘要文件
        Path summariesDir = getProjectPath(projectId).resolve("summaries");
        if (Files.exists(summariesDir)) {
            try (Stream<Path> paths = Files.list(summariesDir)) {
                for (Path p : paths.filter(f -> f.toString().endsWith("_summary.yaml")).toList()) {
                    String name = p.getFileName().toString().replace("_summary.yaml", "");
                    if (canonicalizeChapterId(name).equals(canonical)) {
                        Files.delete(p);
                        deleted = true;
                    }
                }
            }
        }
        
        return deleted;
    }
    
    // ========== 场景简报 ==========
    
    public void saveSceneBrief(String projectId, String chapter, SceneBrief brief) throws IOException {
        String canonical = canonicalizeChapterId(chapter);
        Path filePath = getProjectPath(projectId).resolve("drafts").resolve(canonical).resolve("scene_brief.yaml");
        writeYaml(filePath, brief);
    }
    
    public Optional<SceneBrief> getSceneBrief(String projectId, String chapter) {
        String resolved = resolveChapterDirName(projectId, chapter);
        Path filePath = getProjectPath(projectId).resolve("drafts").resolve(resolved).resolve("scene_brief.yaml");
        
        if (!Files.exists(filePath)) {
            return Optional.empty();
        }
        
        try {
            return Optional.of(readYaml(filePath, SceneBrief.class));
        } catch (IOException e) {
            log.error("读取场景简报失败: {}", filePath, e);
            return Optional.empty();
        }
    }

    public void saveReview(String projectId, String chapter, ReviewResult review) throws IOException {
        String canonical = canonicalizeChapterId(chapter);
        review.setChapter(canonical);
        Path filePath = getProjectPath(projectId).resolve("drafts").resolve(canonical).resolve("review.yaml");
        writeYaml(filePath, review);
    }

    public Optional<ReviewResult> getReview(String projectId, String chapter) {
        String resolved = resolveChapterDirName(projectId, chapter);
        Path filePath = getProjectPath(projectId).resolve("drafts").resolve(resolved).resolve("review.yaml");
        if (!Files.exists(filePath)) {
            return Optional.empty();
        }
        try {
            ReviewResult review = readYaml(filePath, ReviewResult.class);
            review.setChapter(canonicalizeChapterId(chapter));
            return Optional.of(review);
        } catch (IOException e) {
            log.error("读取审稿结果失败: {}", filePath, e);
            return Optional.empty();
        }
    }
    
    // ========== 草稿版本管理 ==========
    
    /**
     * 列出草稿版本
     */
    public List<String> listDraftVersions(String projectId, String chapter) {
        String resolved = resolveChapterDirName(projectId, chapter);
        Path draftDir = getProjectPath(projectId).resolve("drafts").resolve(resolved);
        
        if (!Files.exists(draftDir)) {
            return new ArrayList<>();
        }
        
        List<String> versions = new ArrayList<>();
        
        try (Stream<Path> paths = Files.list(draftDir)) {
            paths.filter(p -> p.getFileName().toString().matches("draft_v\\d+\\.md"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        String version = name.replace("draft_", "").replace(".md", "");
                        versions.add(version);
                    });
        } catch (IOException e) {
            log.error("列出草稿版本失败: {}", draftDir, e);
        }
        
        // 如果有 final.md，添加 current 版本
        if (Files.exists(draftDir.resolve("final.md"))) {
            if (versions.isEmpty()) {
                versions.add("v1");
            }
        }
        
        return versions;
    }
    
    /**
     * 获取指定版本的草稿
     */
    public Optional<Draft> getDraft(String projectId, String chapter, String version) {
        String resolved = resolveChapterDirName(projectId, chapter);
        Path draftDir = getProjectPath(projectId).resolve("drafts").resolve(resolved);
        
        if (!Files.exists(draftDir)) {
            return Optional.empty();
        }
        
        // 尝试读取指定版本
        Path versionPath = draftDir.resolve("draft_" + version + ".md");
        if (Files.exists(versionPath)) {
            return readDraftFromFile(versionPath, chapter, version);
        }
        
        // 回退到 final.md
        Path finalPath = draftDir.resolve("final.md");
        if (Files.exists(finalPath)) {
            return readDraftFromFile(finalPath, chapter, version);
        }
        
        return Optional.empty();
    }
    
    /**
     * 保存最终稿
     */
    public void saveFinalDraft(String projectId, String chapter, String content) throws IOException {
        String canonical = canonicalizeChapterId(chapter);
        Path finalPath = getFinalPath(projectId, canonical);
        
        // 备份旧版本
        Path historyDir = finalPath.getParent().resolve("history");
        if (Files.exists(finalPath)) {
            rotateDraftHistory(finalPath, historyDir);
        }
        
        // 写入最终稿
        writeText(finalPath, content);
        
        // 更新元数据
        Draft draft = Draft.builder()
                .chapter(canonical)
                .version("final")
                .content(content)
                .wordCount(content.length())
                .createdAt(LocalDateTime.now())
                .build();
        
        Path metaPath = finalPath.resolveSibling("final.meta.yaml");
        writeYaml(metaPath, draft);
    }

    public List<Map<String, Object>> searchTextChunks(String projectId, String query, int limit,
                                                      List<String> chapters, List<String> excludeChapters,
                                                      boolean rebuild) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        Set<String> includeSet = normalizeChapterSet(chapters);
        Set<String> excludeSet = normalizeChapterSet(excludeChapters);
        List<Map<String, Object>> results = new ArrayList<>();

        for (String chapter : listChapters(projectId)) {
            String canonical = canonicalizeChapterId(chapter);
            if (!includeSet.isEmpty() && !includeSet.contains(canonical)) {
                continue;
            }
            if (excludeSet.contains(canonical)) {
                continue;
            }

            Optional<String> draftOpt = getFinalDraft(projectId, canonical);
            if (draftOpt.isEmpty()) {
                continue;
            }

            List<String> chunks = splitIntoChunks(draftOpt.get());
            for (int i = 0; i < chunks.size(); i++) {
                String chunk = chunks.get(i);
                double score = scoreChunk(query, chunk);
                if (score <= 0) {
                    continue;
                }

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("chapter", canonical);
                item.put("text", chunk);
                item.put("score", score);
                item.put("chunk_index", i);
                item.put("source", Map.of(
                        "chapter", canonical,
                        "chunk_index", i,
                        "field", "final_draft"
                ));
                results.add(item);
            }
        }

        results.sort((a, b) -> Double.compare(
                ((Number) b.getOrDefault("score", 0.0)).doubleValue(),
                ((Number) a.getOrDefault("score", 0.0)).doubleValue()
        ));
        if (results.size() > limit) {
            return new ArrayList<>(results.subList(0, limit));
        }
        return results;
    }

    public Map<String, Object> rebuildTextChunkIndex(String projectId) {
        int chaptersProcessed = 0;
        int chunkTotal = 0;
        for (String chapter : listChapters(projectId)) {
            Optional<String> draftOpt = getFinalDraft(projectId, chapter);
            if (draftOpt.isEmpty()) {
                continue;
            }
            chaptersProcessed++;
            chunkTotal += splitIntoChunks(draftOpt.get()).size();
        }
        return Map.of(
                "chapters_processed", chaptersProcessed,
                "chunks_total", chunkTotal
        );
    }
    
    // ========== 章节摘要 ==========
    
    public void saveChapterSummary(String projectId, ChapterSummary summary) throws IOException {
        String canonical = canonicalizeChapterId(summary.getChapter());
        summary.setChapter(canonical);
        
        if (summary.getVolumeId() == null || summary.getVolumeId().isEmpty()) {
            summary.setVolumeId(extractVolumeId(canonical));
        }
        
        Path filePath = getProjectPath(projectId).resolve("summaries").resolve(canonical + "_summary.yaml");
        writeYaml(filePath, summary);
    }
    
    public Optional<ChapterSummary> getChapterSummary(String projectId, String chapter) {
        String canonical = canonicalizeChapterId(chapter);
        Path filePath = resolveSummaryPath(projectId, chapter);
        
        if (filePath == null || !Files.exists(filePath)) {
            return Optional.empty();
        }
        
        try {
            ChapterSummary summary = readYaml(filePath, ChapterSummary.class);
            summary.setChapter(canonical);
            if (summary.getVolumeId() == null || summary.getVolumeId().isEmpty()) {
                summary.setVolumeId(extractVolumeId(canonical));
            }
            return Optional.of(summary);
        } catch (IOException e) {
            log.error("读取章节摘要失败: {}", filePath, e);
            return Optional.empty();
        }
    }
    
    public List<ChapterSummary> listChapterSummaries(String projectId, String volumeId) {
        Path summariesDir = getProjectPath(projectId).resolve("summaries");
        if (!Files.exists(summariesDir)) {
            return new ArrayList<>();
        }
        
        Map<String, ChapterSummary> summaries = new HashMap<>();
        
        try (Stream<Path> paths = Files.list(summariesDir)) {
            for (Path p : paths.filter(f -> f.toString().endsWith("_summary.yaml")).toList()) {
                try {
                    ChapterSummary summary = readYaml(p, ChapterSummary.class);
                    String canonical = canonicalizeChapterId(
                            summary.getChapter() != null ? summary.getChapter() : 
                            p.getFileName().toString().replace("_summary.yaml", ""));
                    summary.setChapter(canonical);
                    
                    if (summary.getVolumeId() == null || summary.getVolumeId().isEmpty()) {
                        summary.setVolumeId(extractVolumeId(canonical));
                    }
                    
                    if (volumeId != null && !volumeId.equals(summary.getVolumeId())) {
                        continue;
                    }
                    
                    summaries.put(canonical, summary);
                } catch (IOException e) {
                    log.error("读取章节摘要失败: {}", p, e);
                }
            }
        } catch (IOException e) {
            log.error("列出章节摘要失败: {}", summariesDir, e);
        }
        
        List<ChapterSummary> result = new ArrayList<>(summaries.values());
        result.sort((a, b) -> {
            int volCmp = compareVolumeIds(a.getVolumeId(), b.getVolumeId());
            if (volCmp != 0) return volCmp;
            
            if (a.getOrderIndex() != null && b.getOrderIndex() != null) {
                return a.getOrderIndex().compareTo(b.getOrderIndex());
            }
            
            return compareChapterIds(a.getChapter(), b.getChapter());
        });
        
        return result;
    }
    
    // ========== 冲突报告 ==========

    /**
     * 保存冲突报告（对齐 Python draft_storage.save_conflict_report）
     * 存储路径：data/{projectId}/drafts/{chapter}/conflict_report.yaml
     */
    public void saveConflictReport(String projectId, String chapter, Map<String, Object> report) throws IOException {
        String canonical = canonicalizeChapterId(chapter);
        Path filePath = getProjectPath(projectId).resolve("drafts").resolve(canonical).resolve("conflict_report.yaml");
        writeYaml(filePath, report);
    }

    // ========== 分卷摘要 ==========

    /**
     * 保存分卷摘要（对齐 Python draft_storage.volume_storage.save_volume_summary）
     * 存储路径：data/{projectId}/summaries/volumes/{volumeId}_summary.yaml
     */
    public void saveVolumeSummary(String projectId, Map<String, Object> summary) throws IOException {
        String volumeId = (String) summary.getOrDefault("volume_id", "V1");
        if (volumeId == null || volumeId.isBlank()) volumeId = "V1";
        Path filePath = getProjectPath(projectId).resolve("summaries").resolve("volumes")
                .resolve(volumeId + "_summary.yaml");
        writeYaml(filePath, summary);
    }

    /**
     * 读取分卷摘要
     */
    public Optional<Map<String, Object>> getVolumeSummary(String projectId, String volumeId) {
        Path filePath = getProjectPath(projectId).resolve("summaries").resolve("volumes")
                .resolve(volumeId + "_summary.yaml");
        if (!Files.exists(filePath)) return Optional.empty();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> summary = readYaml(filePath, Map.class);
            return Optional.ofNullable(summary);
        } catch (IOException e) {
            log.error("读取分卷摘要失败: {}", filePath, e);
            return Optional.empty();
        }
    }

    // ========== 辅助方法 ==========

    private Path getFinalPath(String projectId, String chapter) {        return getProjectPath(projectId).resolve("drafts").resolve(chapter).resolve("final.md");
    }
    
    private String resolveChapterDirName(String projectId, String chapter) {
        String canonical = canonicalizeChapterId(chapter);
        Path draftsDir = getProjectPath(projectId).resolve("drafts");
        
        if (Files.exists(draftsDir)) {
            // 先尝试规范化名称
            if (Files.exists(draftsDir.resolve(canonical))) {
                return canonical;
            }
            // 再尝试原始名称
            if (Files.exists(draftsDir.resolve(chapter))) {
                return chapter;
            }
            // 遍历查找
            try (Stream<Path> paths = Files.list(draftsDir)) {
                for (Path p : paths.filter(Files::isDirectory).toList()) {
                    if (canonicalizeChapterId(p.getFileName().toString()).equals(canonical)) {
                        return p.getFileName().toString();
                    }
                }
            } catch (IOException e) {
                log.error("解析章节目录失败", e);
            }
        }
        
        return canonical;
    }
    
    private Path resolveSummaryPath(String projectId, String chapter) {
        String canonical = canonicalizeChapterId(chapter);
        Path summariesDir = getProjectPath(projectId).resolve("summaries");
        
        if (Files.exists(summariesDir)) {
            Path canonicalPath = summariesDir.resolve(canonical + "_summary.yaml");
            if (Files.exists(canonicalPath)) {
                return canonicalPath;
            }
            
            Path rawPath = summariesDir.resolve(chapter + "_summary.yaml");
            if (Files.exists(rawPath)) {
                return rawPath;
            }
        }
        
        return summariesDir.resolve(canonical + "_summary.yaml");
    }
    
    private String canonicalizeChapterId(String chapterId) {
        if (chapterId == null || chapterId.isBlank()) {
            return "";
        }
        
        String normalized = chapterId.trim().toUpperCase();
        Matcher matcher = CHAPTER_ID_PATTERN.matcher(normalized);
        if (matcher.matches()) {
            int vol = Integer.parseInt(matcher.group(1));
            int ch = Integer.parseInt(matcher.group(2));
            return String.format("V%dC%d", vol, ch);
        }
        
        return chapterId.trim();
    }
    
    private String extractVolumeId(String chapterId) {
        Matcher matcher = CHAPTER_ID_PATTERN.matcher(chapterId);
        if (matcher.matches()) {
            return "V" + matcher.group(1);
        }
        return "V1";
    }
    
    private int compareChapterIds(String a, String b) {
        Matcher ma = CHAPTER_ID_PATTERN.matcher(a);
        Matcher mb = CHAPTER_ID_PATTERN.matcher(b);
        
        if (ma.matches() && mb.matches()) {
            int volA = Integer.parseInt(ma.group(1));
            int volB = Integer.parseInt(mb.group(1));
            if (volA != volB) return Integer.compare(volA, volB);
            
            int chA = Integer.parseInt(ma.group(2));
            int chB = Integer.parseInt(mb.group(2));
            return Integer.compare(chA, chB);
        }
        
        return a.compareTo(b);
    }
    
    private int compareVolumeIds(String a, String b) {
        try {
            int volA = Integer.parseInt(a.substring(1));
            int volB = Integer.parseInt(b.substring(1));
            return Integer.compare(volA, volB);
        } catch (Exception e) {
            return a.compareTo(b);
        }
    }
    
    private Optional<Draft> readDraftFromFile(Path filePath, String chapter, String version) {
        try {
            String content = readText(filePath);
            String canonical = canonicalizeChapterId(chapter);
            
            // 尝试读取元数据
            Path metaPath = filePath.resolveSibling(filePath.getFileName().toString().replace(".md", ".meta.yaml"));
            if (Files.exists(metaPath)) {
                Draft draft = readYaml(metaPath, Draft.class);
                draft.setChapter(canonical);
                draft.setContent(content);
                return Optional.of(draft);
            }
            
            return Optional.of(Draft.builder()
                    .chapter(canonical)
                    .version(version)
                    .content(content)
                    .wordCount(content.length())
                    .createdAt(LocalDateTime.now())
                    .build());
        } catch (IOException e) {
            log.error("读取草稿文件失败: {}", filePath, e);
            return Optional.empty();
        }
    }
    
    private void rotateDraftHistory(Path finalPath, Path historyDir) throws IOException {
        ensureDir(historyDir);
        
        String ts = LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"));
        Path backupPath = historyDir.resolve("final_" + ts + ".md");
        
        Files.move(finalPath, backupPath);
        
        // 清理旧备份
        try (Stream<Path> paths = Files.list(historyDir)) {
            List<Path> backups = paths
                    .filter(p -> p.getFileName().toString().startsWith("final_") && p.toString().endsWith(".md"))
                    .sorted(Comparator.comparingLong(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            return 0;
                        }
                    }))
                    .toList();
            
            while (backups.size() > MAX_DRAFT_PREV_BACKUPS) {
                Files.deleteIfExists(backups.get(0));
                backups = backups.subList(1, backups.size());
            }
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

    private Set<String> normalizeChapterSet(List<String> chapters) {
        if (chapters == null || chapters.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String chapter : chapters) {
            String canonical = canonicalizeChapterId(chapter);
            if (!canonical.isBlank()) {
                normalized.add(canonical);
            }
        }
        return normalized;
    }

    private List<String> splitIntoChunks(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        String[] paragraphs = content.split("\\R\\s*\\R+");
        StringBuilder current = new StringBuilder();
        for (String raw : paragraphs) {
            String paragraph = raw.trim();
            if (paragraph.isBlank()) {
                continue;
            }
            if (current.length() > 0 && current.length() + paragraph.length() + 2 > 420) {
                chunks.add(current.toString().trim());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(paragraph);
        }
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }

    private double scoreChunk(String query, String chunk) {
        Set<String> queryTerms = tokenize(query);
        Set<String> chunkTerms = tokenize(chunk);
        if (queryTerms.isEmpty() || chunkTerms.isEmpty()) {
            return 0.0;
        }
        int overlap = 0;
        for (String term : queryTerms) {
            if (chunkTerms.contains(term)) {
                overlap++;
            }
        }
        if (overlap == 0) {
            return 0.0;
        }
        double density = (double) overlap / queryTerms.size();
        double lengthFactor = Math.min(1.0, chunk.length() / 180.0);
        return density * 3.0 + lengthFactor;
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        String lower = text.toLowerCase();
        Matcher cn = Pattern.compile("[\\p{IsHan}]{2,4}").matcher(lower);
        while (cn.find()) {
            terms.add(cn.group());
        }
        Matcher words = Pattern.compile("[a-z0-9_]{2,}").matcher(lower);
        while (words.find()) {
            terms.add(words.group());
        }
        return terms;
    }
}
