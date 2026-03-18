package com.wenshape.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;

/**
 * 编辑建议请求
 */
@Data
public class EditSuggestRequest {
    private String chapter;
    private String content;
    private String instruction;

    @JsonProperty("rejected_entities")
    private List<String> rejectedEntities;

    @JsonProperty("context_mode")
    private String contextMode = "quick";

    @JsonProperty("selection_text")
    private String selectionText;

    @JsonProperty("selection_start")
    private Integer selectionStart;

    @JsonProperty("selection_end")
    private Integer selectionEnd;
}
