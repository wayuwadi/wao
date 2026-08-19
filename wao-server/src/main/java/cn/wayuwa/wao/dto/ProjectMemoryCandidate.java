package cn.wayuwa.wao.dto;

import java.util.List;

/** Runner 从非 Git 运行产物中解析出的结构化项目记忆候选。 */
public record ProjectMemoryCandidate(
        String category,
        String title,
        String content,
        List<String> evidenceLinks) {
}
