package com.wenshape.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 世界观卡片
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorldCard {
    
    private String name;
    private String description;
    
    @Builder.Default
    private List<String> aliases = new ArrayList<>();
    
    private String category;
    
    @Builder.Default
    private List<String> rules = new ArrayList<>();
    
    private Boolean immutable;
    
    @Builder.Default
    private Integer stars = 1;
}
