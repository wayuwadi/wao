package cn.wayuwa.wao.dto;

import java.util.List;

/** AI Run 详情展示的一条项目记忆引用。 */
public record ProjectMemoryReferenceDto(
        String id,
        String projectId,
        String category,
        String title,
        String content,
        String sourceTaskId,
        String sourceRunId,
        List<String> evidenceLinks) {
}
