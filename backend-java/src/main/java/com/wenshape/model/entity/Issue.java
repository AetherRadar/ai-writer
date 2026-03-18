package com.wenshape.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 审稿问题项
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Issue {

    private String severity;
    private String category;
    private String location;
    private String problem;
    private String suggestion;
}
