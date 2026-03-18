package com.wenshape.controller;

import com.wenshape.agent.ArchivistAgent;
import com.wenshape.llm.LlmGateway;
import com.wenshape.storage.CardStorage;
import com.wenshape.storage.CanonStorage;
import com.wenshape.storage.DraftStorage;
import com.wenshape.storage.ProjectStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 同人创作控制器 - Wiki 搜索、爬取和卡片生成
 */
@Slf4j
@RestController
@RequestMapping("/fanfiction")
@RequiredArgsConstructor
public class FanfictionController {
    
    private final CardStorage cardStorage;
    private final CanonStorage canonStorage;
    private final DraftStorage draftStorage;
    private final ProjectStorage projectStorage;
    private final LlmGateway llmGateway;
    
    private static final int MAX_BATCH_SIZE = 80;
    private static final Duration SCRAPE_TIMEOUT = Duration.ofSeconds(30);
    
    /**
     * 搜索 Wiki
     */
    @PostMapping("/search")
    public List<Map<String, Object>> searchWikis(@RequestBody SearchRequest request) {
        String query = request.query;
        String engine = request.engine != null ? request.engine : "moegirl";
        
        List<Map<String, Object>> results = new ArrayList<>();
        
        try {
            if ("moegirl".equalsIgnoreCase(engine)) {
                results = searchMoegirl(query, 10);
            } else if ("wikipedia".equalsIgnoreCase(engine)) {
                results = searchWikipedia(query, 10);
            }
        } catch (Exception e) {
            log.error("Wiki 搜索失败: {}", e.getMessage(), e);
        }
        
        return results;
    }
    
    /**
     * 预览页面
     */
    @PostMapping("/preview")
    public Map<String, Object> previewPage(@RequestBody PreviewRequest request) {
        String url = request.url;
        
        if (!isHttpUrl(url)) {
            return Map.of("success", false, "error", "仅支持 http/https 链接。");
        }
        
        try {
            return scrapePage(url);
        } catch (Exception e) {
            log.error("页面爬取失败: {}", e.getMessage(), e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }
    
    /**
     * 提取卡片
     */
    @PostMapping("/extract")
    public Mono<Map<String, Object>> extractCards(@RequestBody ExtractRequest request) {
        String projectId = request.projectId;
        String url = request.url;
        String title = request.title != null ? request.title : "";
        String content = request.content != null ? request.content : "";
        
        // 如果提供了 URL，先爬取
        if (url != null && !url.isEmpty()) {
            if (!isHttpUrl(url)) {
                return Mono.just(Map.of("success", false, "error", "仅支持 http/https 链接。", "proposals", List.of()));
            }
            
            try {
                Map<String, Object> crawlResult = scrapePage(url);
                if (!(boolean) crawlResult.get("success")) {
                    return Mono.just(Map.of("success", false, "error", crawlResult.get("error"), "proposals", List.of()));
                }
                title = (String) crawlResult.getOrDefault("title", title);
                content = (String) crawlResult.getOrDefault("llm_content", 
                        crawlResult.getOrDefault("content", content));
            } catch (Exception e) {
                return Mono.just(Map.of("success", false, "error", e.getMessage(), "proposals", List.of()));
            }
        }
        
        if (content == null || content.isEmpty()) {
            return Mono.just(Map.of("success", false, "error", "没有可提取的内容。", "proposals", List.of()));
        }
        
        String language = resolveProjectLanguage(projectId, request.language);
        String finalTitle = title;
        String finalContent = content;
        String finalUrl = url;
        
        return extractFanfictionCard(finalTitle, finalContent, language)
                .map(proposal -> {
                    proposal.put("source_url", finalUrl);
                    return Map.<String, Object>of("success", true, "proposals", List.of(proposal));
                })
                .onErrorResume(e -> {
                    log.error("提取失败: {}", e.getMessage(), e);
                    return Mono.just(Map.of("success", false, "error", e.getMessage(), "proposals", List.of()));
                });
    }
    
    /**
     * 批量提取卡片
     */
    @PostMapping("/extract/batch")
    public Mono<Map<String, Object>> batchExtractCards(@RequestBody BatchExtractRequest request) {
        String projectId = request.projectId;
        List<String> urls = request.urls;
        
        if (urls == null || urls.isEmpty()) {
            return Mono.just(Map.of("success", false, "error", "没有提供 URL。", "proposals", List.of()));
        }
        
        if (urls.size() > MAX_BATCH_SIZE) {
            return Mono.just(Map.of("success", false, 
                    "error", String.format("一次最多提取 %d 个链接，请分批操作。", MAX_BATCH_SIZE), 
                    "proposals", List.of()));
        }
        
        // 检查 URL 有效性
        List<String> invalid = urls.stream().filter(u -> !isHttpUrl(u)).toList();
        if (!invalid.isEmpty()) {
            return Mono.just(Map.of("success", false, 
                    "error", "存在非 http/https 链接，请取消勾选后重试。", 
                    "proposals", List.of()));
        }
        
        String language = resolveProjectLanguage(projectId, request.language);
        
        // 并发爬取和提取
        return Flux.fromIterable(urls)
                .flatMap(url -> {
                    try {
                        Map<String, Object> crawlResult = scrapePage(url);
                        if (!(boolean) crawlResult.get("success")) {
                            return Mono.empty();
                        }
                        
                        String title = (String) crawlResult.getOrDefault("title", "");
                        String content = (String) crawlResult.getOrDefault("llm_content", 
                                crawlResult.getOrDefault("content", ""));
                        
                        if (content == null || content.isEmpty()) {
                            return Mono.empty();
                        }
                        
                        return extractFanfictionCard(title, content, language)
                                .map(proposal -> {
                                    proposal.put("source_url", url);
                                    return proposal;
                                })
                                .onErrorResume(e -> Mono.empty());
                    } catch (Exception e) {
                        return Mono.empty();
                    }
                }, 4) // 并发度 4
                .collectList()
                .map(proposals -> {
                    if (proposals.isEmpty()) {
                        return Map.<String, Object>of("success", false, "error", "No extractable pages", "proposals", List.of());
                    }
                    return Map.<String, Object>of("success", true, "proposals", proposals);
                });
    }
    
    // ========== Wiki 搜索 ==========
    
    private List<Map<String, Object>> searchMoegirl(String query, int maxResults) throws IOException {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String searchUrl = "https://zh.moegirl.org.cn/index.php?search=" + encodedQuery;
        
        Document doc = Jsoup.connect(searchUrl)
                .userAgent("Mozilla/5.0 (compatible; WenShape/1.0)")
                .timeout(10000)
                .get();
        
        List<Map<String, Object>> results = new ArrayList<>();
        Elements searchResults = doc.select(".mw-search-result");
        
        for (Element result : searchResults) {
            if (results.size() >= maxResults) break;
            
            Element titleLink = result.selectFirst(".mw-search-result-heading a");
            Element snippet = result.selectFirst(".searchresult");
            
            if (titleLink != null) {
                String title = titleLink.text();
                String href = titleLink.attr("href");
                String url = "https://zh.moegirl.org.cn" + href;
                String snippetText = snippet != null ? snippet.text() : "";
                
                results.add(Map.of(
                        "title", title,
                        "url", url,
                        "snippet", snippetText,
                        "source", "moegirl"
                ));
            }
        }
        
        return results;
    }
    
    private List<Map<String, Object>> searchWikipedia(String query, int maxResults) throws IOException {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String apiUrl = "https://zh.wikipedia.org/w/api.php?action=query&list=search&srsearch=" 
                + encodedQuery + "&format=json&srlimit=" + maxResults;
        
        // 简化实现，使用 Jsoup 获取 JSON
        String json = Jsoup.connect(apiUrl)
                .ignoreContentType(true)
                .userAgent("Mozilla/5.0 (compatible; WenShape/1.0)")
                .timeout(10000)
                .execute()
                .body();
        
        // 简单解析 JSON（实际应使用 Jackson）
        List<Map<String, Object>> results = new ArrayList<>();
        // TODO: 使用 Jackson 解析 JSON
        
        return results;
    }
    
    // ========== 页面爬取 ==========
    
    private Map<String, Object> scrapePage(String url) throws IOException {
        Document doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (compatible; WenShape/1.0)")
                .timeout(30000)
                .get();
        
        String title = doc.title();
        
        // 移除不需要的元素
        doc.select("script, style, nav, footer, .navbox, .infobox, .toc, .mw-editsection").remove();
        
        // 获取主要内容
        Element content = doc.selectFirst("#mw-content-text, .mw-parser-output, article, main, .content");
        String textContent = content != null ? content.text() : doc.body().text();
        
        // 提取链接
        List<Map<String, String>> links = new ArrayList<>();
        Elements linkElements = doc.select("a[href]");
        for (Element link : linkElements) {
            String href = link.absUrl("href");
            String linkText = link.text();
            if (!href.isEmpty() && !linkText.isEmpty() && href.startsWith("http")) {
                links.add(Map.of("url", href, "text", linkText));
            }
            if (links.size() >= 50) break;
        }
        
        // 判断是否为列表页
        boolean isListPage = doc.select("ul li a, ol li a").size() > 20;
        
        // 为 LLM 准备的精简内容
        String llmContent = textContent;
        if (llmContent.length() > 8000) {
            llmContent = llmContent.substring(0, 8000) + "...";
        }
        
        return Map.of(
                "success", true,
                "title", title,
                "content", textContent,
                "llm_content", llmContent,
                "links", links,
                "is_list_page", isListPage,
                "url", url
        );
    }
    
    // ========== 卡片提取 ==========
    
    private Mono<Map<String, Object>> extractFanfictionCard(String title, String content, String language) {
        // 构建提示词
        String prompt = buildExtractionPrompt(title, content, language);
        
        return llmGateway.generate(prompt, "archivist", 2000)
                .map(response -> {
                    // 解析 LLM 响应
                    return parseCardProposal(response, title, language);
                });
    }
    
    private String buildExtractionPrompt(String title, String content, String language) {
        String langInstruction = "zh".equals(language) ? 
                "请用中文回复。" : "Please respond in English.";
        
        return String.format("""
                %s
                
                根据以下 Wiki 页面内容，提取角色或世界观设定信息，生成一张卡片。
                
                页面标题: %s
                
                页面内容:
                %s
                
                请按以下格式输出:
                
                类型: [character 或 world]
                名称: [角色名或设定名]
                别名: [逗号分隔的别名列表，可选]
                描述: [详细描述，包含关键特征、背景、能力等]
                分类: [仅 world 类型需要，如 location/item/organization/concept]
                重要度: [1-3 星，3 最重要]
                """, langInstruction, title, content);
    }
    
    private Map<String, Object> parseCardProposal(String response, String title, String language) {
        Map<String, Object> proposal = new LinkedHashMap<>();
        
        // 简单解析
        String type = "character";
        String name = title;
        String description = "";
        List<String> aliases = new ArrayList<>();
        String category = null;
        int stars = 2;
        
        String[] lines = response.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("类型:") || line.startsWith("Type:")) {
                String value = line.substring(line.indexOf(":") + 1).trim().toLowerCase();
                if (value.contains("world") || value.contains("世界")) {
                    type = "world";
                }
            } else if (line.startsWith("名称:") || line.startsWith("Name:")) {
                name = line.substring(line.indexOf(":") + 1).trim();
            } else if (line.startsWith("别名:") || line.startsWith("Aliases:")) {
                String aliasStr = line.substring(line.indexOf(":") + 1).trim();
                if (!aliasStr.isEmpty()) {
                    aliases = Arrays.asList(aliasStr.split("[,，]"));
                }
            } else if (line.startsWith("描述:") || line.startsWith("Description:")) {
                description = line.substring(line.indexOf(":") + 1).trim();
            } else if (line.startsWith("分类:") || line.startsWith("Category:")) {
                category = line.substring(line.indexOf(":") + 1).trim();
            } else if (line.startsWith("重要度:") || line.startsWith("Stars:")) {
                String starsStr = line.substring(line.indexOf(":") + 1).trim();
                try {
                    stars = Integer.parseInt(starsStr.replaceAll("[^0-9]", ""));
                    stars = Math.max(1, Math.min(3, stars));
                } catch (NumberFormatException ignored) {}
            }
        }
        
        // 如果描述为空，使用响应的剩余部分
        if (description.isEmpty()) {
            description = response;
        }
        
        proposal.put("type", type);
        proposal.put("name", name);
        proposal.put("aliases", aliases);
        proposal.put("description", description);
        proposal.put("stars", stars);
        
        if ("world".equals(type) && category != null) {
            proposal.put("category", category);
        }
        
        return proposal;
    }
    
    // ========== 辅助方法 ==========
    
    private boolean isHttpUrl(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            return ("http".equals(scheme) || "https".equals(scheme)) && uri.getHost() != null;
        } catch (Exception e) {
            return false;
        }
    }
    
    private String resolveProjectLanguage(String projectId, String requestLanguage) {
        // 优先使用请求中的语言
        if (requestLanguage != null && !requestLanguage.isBlank()) {
            String lang = requestLanguage.trim().toLowerCase();
            if (lang.startsWith("en")) return "en";
            if (lang.startsWith("zh")) return "zh";
        }
        
        // 从项目配置读取
        try {
            var projectOpt = projectStorage.getProject(projectId);
            if (projectOpt.isPresent()) {
                String lang = projectOpt.get().getLanguage();
                if (lang != null) {
                    lang = lang.trim().toLowerCase();
                    if (lang.startsWith("en")) return "en";
                    if (lang.startsWith("zh")) return "zh";
                }
            }
        } catch (Exception e) {
            log.warn("读取项目语言失败: {}", e.getMessage());
        }
        
        return "zh";
    }
    
    // ========== 请求类 ==========
    
    public static class SearchRequest {
        public String query;
        public String engine;
    }
    
    public static class PreviewRequest {
        public String url;
    }
    
    public static class ExtractRequest {
        public String projectId;
        public String language;
        public String url;
        public String title;
        public String content;
        public Integer maxCards;
    }
    
    public static class BatchExtractRequest {
        public String projectId;
        public String language;
        public List<String> urls;
    }
}
