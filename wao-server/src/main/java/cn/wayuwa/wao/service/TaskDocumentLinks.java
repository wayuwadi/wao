package cn.wayuwa.wao.service;

import java.util.LinkedHashSet;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 只从任务与子任务明确保存的关联中提取文档，不推断整个知识库的归属。 */
final class TaskDocumentLinks {
    private TaskDocumentLinks() { }

    static Set<String> ids(String detailJson, ObjectMapper json) {
        Set<String> ids = new LinkedHashSet<>();
        if (detailJson == null || detailJson.isBlank()) return ids;
        try {
            JsonNode detail = json.readTree(detailJson);
            if (detail == null) return ids;
            add(ids, detail.path("docs"));
            JsonNode subs = detail.path("subs");
            if (subs.isArray()) subs.forEach(sub -> add(ids, sub.path("docs")));
        } catch (JsonProcessingException ignored) {
            // 历史损坏详情不扩大关联范围，正常任务仍可独立展示。
        }
        return ids;
    }

    private static void add(Set<String> ids, JsonNode docs) {
        if (!docs.isArray()) return;
        docs.forEach(doc -> {
            if (doc.isTextual() && !doc.asText().isBlank()) ids.add(doc.asText());
        });
    }
}
