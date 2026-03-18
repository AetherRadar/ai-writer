package com.wenshape.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;

/**
 * 用户反馈请求
 */
@Data
public class FeedbackRequest {
    private String chapter;
    private String feedback;
    private String action = "revise";

    @JsonProperty("rejected_entities")
    private List<String> rejectedEntities;
}
