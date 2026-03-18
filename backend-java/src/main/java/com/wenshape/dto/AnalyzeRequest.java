package com.wenshape.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 章节分析请求
 */
@Data
public class AnalyzeRequest {
    private String language;
    private String chapter;
    private String content;

    @JsonProperty("chapter_title")
    private String chapterTitle;
}
