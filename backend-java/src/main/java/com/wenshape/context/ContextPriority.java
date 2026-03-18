package com.wenshape.context;

/**
 * 上下文优先级
 * CRITICAL: 必须包含，不可压缩
 * HIGH: 优先包含，可少量压缩
 * MEDIUM: 按需包含，可压缩
 * LOW: 可选包含，可大量压缩或省略
 */
public enum ContextPriority {
    CRITICAL(1),
    HIGH(2),
    MEDIUM(3),
    LOW(4);
    
    private final int value;
    
    ContextPriority(int value) {
        this.value = value;
    }
    
    public int getValue() {
        return value;
    }
}
