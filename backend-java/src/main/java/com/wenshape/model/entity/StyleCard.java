package com.wenshape.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文风卡片
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StyleCard {
    
    private String style;
}
