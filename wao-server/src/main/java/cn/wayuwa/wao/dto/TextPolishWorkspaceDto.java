package cn.wayuwa.wao.dto;

import java.util.List;

/** 独立润色工作台首屏所需的成员提示词与真实历史。 */
public record TextPolishWorkspaceDto(
        String prompt,
        List<TextPolishRecordDto> records) { }
