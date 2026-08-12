package cn.wayuwa.wao.dto;

import java.util.List;

/** 一条行动及其当前查看日期的完成状态。 */
public record ActionPlanItemDto(String id, String title, List<Integer> weekdays, boolean active,
        boolean scheduled, boolean completed) { }
