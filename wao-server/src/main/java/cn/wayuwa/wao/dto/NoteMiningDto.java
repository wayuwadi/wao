package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 小记 AI 挖掘任务及其可解释内容产物。 */
public record NoteMiningDto(Job job, List<Artifact> artifacts) {
    public record Job(String id, String status, String message, int sourceCount, LocalDateTime createdAt) { }
    public record Artifact(String id, String type, String content, String rationale, String feedbackStatus,
                           List<Source> sources, LocalDateTime createdAt) { }
    public record Source(String docId, int docRev, String title, String excerpt) { }
}
