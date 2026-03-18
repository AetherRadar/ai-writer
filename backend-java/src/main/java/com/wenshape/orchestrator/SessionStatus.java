package com.wenshape.orchestrator;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 会话状态枚举
 */
public enum SessionStatus {
    IDLE("idle"),
    GENERATING_BRIEF("generating_brief"),
    WAITING_USER_INPUT("waiting_user_input"),
    WRITING_DRAFT("writing_draft"),
    EDITING("editing"),
    WAITING_FEEDBACK("waiting_feedback"),
    ANALYZING("analyzing"),
    COMPLETED("completed"),
    ERROR("error");
    
    private final String value;
    
    SessionStatus(String value) {
        this.value = value;
    }
    
    @JsonValue
    public String getValue() {
        return value;
    }
}
