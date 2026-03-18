package com.wenshape.context;

/**
 * 上下文类型分类
 */
public enum ContextType {
    // 指导性上下文
    SYSTEM_PROMPT("system_prompt"),
    TASK_INSTRUCTION("task_instruction"),
    OUTPUT_SCHEMA("output_schema"),
    STYLE_CARD("style_card"),
    
    // 信息性上下文
    CHARACTER_CARD("character_card"),
    WORLD_CARD("world_card"),
    FACT("fact"),
    TIMELINE_EVENT("timeline_event"),
    CHAPTER_SUMMARY("chapter_summary"),
    SCENE_BRIEF("scene_brief"),
    DRAFT("draft"),
    TEXT_CHUNK("text_chunk"),
    
    // 行动性上下文
    TOOL_DEFINITION("tool_definition"),
    TOOL_TRACE("tool_trace");
    
    private final String value;
    
    ContextType(String value) {
        this.value = value;
    }
    
    public String getValue() {
        return value;
    }
}
