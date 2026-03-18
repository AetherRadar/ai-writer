package com.wenshape.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;

/**
 * 开始写作会话请求
 */
@Data
public class StartSessionRequest {
    private String language;
    private String chapter;

    @JsonProperty("chapter_title")
    private String chapterTitle;

    @JsonProperty("chapter_goal")
    private String chapterGoal;

    @JsonProperty("target_word_count")
    private Integer targetWordCount = 3000;

    @JsonProperty("character_names")
    private List<String> characterNames;
}
