package cn.wayuwa.wao.dto;

import java.util.List;
import jakarta.validation.constraints.NotBlank;

/** 点子库完整档案及写接口请求。 */
public record IdeaDto(
        String id, String title, String rawIdea, String problem, String targetOutcome,
        String priority, String stage, String createdBy, String ownerId, String projectId,
        Detail detail, List<Artifact> artifacts, List<TaskLink> tasks,
        List<Iteration> iterations, List<ResearchRun> researchRuns,
        String createdAt, String updatedAt) {

    public record Activity(String action, String by, String at) {}
    public record Detail(String targetUsers, String coreValue, String nonGoals, String feasibility,
            String mvpHypothesis, String acceptanceCriteria, String meetingDate,
            Integer meetingDuration, List<String> meetingParticipants, String meetingConclusion,
            String meetingNotes, String validationConclusion, String validationEvidence,
            String abandonmentReason, String researchAcceptedAt, String researchAcceptedBy,
            List<Activity> activity) {}
    public record Artifact(String id, String kind, String docId, String title, String createdBy, String createdAt) {}
    public record TaskLink(String id, String taskId, String title, String status, boolean requiredMvp,
            int order, String createdBy, String createdAt) {}
    public record Iteration(String id, String taskId, String aiRunId, String objective,
            String acceptanceCriteria, String status, String feedback, String resultSummary,
            String reportUrl, String createdBy, String createdAt, String finishedAt) {}
    public record ResearchRun(String id, String status, String error, String docId,
            String createdBy, String createdAt, String finishedAt) {}

    public record CreateRequest(@NotBlank String title, String rawIdea, String priority) {}
    public record PatchRequest(String title, String rawIdea, String problem, String targetOutcome,
            String priority, String ownerId, String projectId, Detail detail) {}
    public record TransitionRequest(@NotBlank String stage, String validationConclusion,
            String validationEvidence, String abandonmentReason) {}
    public record ArtifactRequest(@NotBlank String kind, @NotBlank String docId, String title) {}
    public record TaskRequest(String taskId, String title, String description, String priority,
            String ownerId, Boolean requiredMvp) {}
    public record IterationRequest(@NotBlank String taskId, @NotBlank String objective,
            String acceptanceCriteria, String aiRunId) {}
    public record IterationPatchRequest(String status, String feedback, String resultSummary, String reportUrl) {}
}
