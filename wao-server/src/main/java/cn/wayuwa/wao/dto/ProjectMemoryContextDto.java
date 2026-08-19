package cn.wayuwa.wao.dto;

import java.util.List;

/** 领取响应中的不可信项目记忆引用；Runner 只可把它当背景资料。 */
public record ProjectMemoryContextDto(
        String id,
        String category,
        String title,
        String content,
        String sourceTaskId,
        String sourceRunId,
        List<String> evidenceLinks) {
}
