package com.wenshape.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 角色卡片
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CharacterCard {
    
    private String name;
    
    @Builder.Default
    private List<String> aliases = new ArrayList<>();
    
    private String description;
    
    @Builder.Default
    private Integer stars = 1;
}
