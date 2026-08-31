package cn.wayuwa.wao.service;

import cn.wayuwa.wao.domain.ProjectEntity;
import cn.wayuwa.wao.domain.ReviewableTaskRunRow;
import cn.wayuwa.wao.dto.ReviewableTaskDto;
import cn.wayuwa.wao.dto.TaskDto;
import cn.wayuwa.wao.mapper.AiTaskRunMapper;
import cn.wayuwa.wao.mapper.ProjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 从任务状态与每条任务最近一次真实 AI Run 派生首页验收队列。 */
@Service
public class TaskReviewService {
    private static final String ACCEPTANCE = "ACCEPTANCE";
    private static final String RETRY = "RETRY";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final TaskService tasks;
    private final AiTaskRunMapper runs;
    private final ProjectMapper projects;

    public TaskReviewService(TaskService tasks, AiTaskRunMapper runs, ProjectMapper projects) {
        this.tasks = tasks;
        this.runs = runs;
        this.projects = projects;
    }

    /**
     * 返回当前成员可见的待验收任务，以及最近一次运行失败且尚未完成的需重试任务。
     *
     * @param memberId 当前登录成员 id
     * @return 需要人工处理的真实任务，需重试项优先、同组内按运行结束时间倒序
     */
    public List<ReviewableTaskDto> list(String memberId) {
        List<TaskDto> visible = tasks.list(memberId);
        if (visible.isEmpty()) return List.of();

        List<ReviewableTaskRunRow> latest = runs.selectLatestRunsForTasks(
                visible.stream().map(TaskDto::id).toList());
        Map<String, ReviewableTaskRunRow> latestByTask = latest.stream()
                .collect(Collectors.toMap(ReviewableTaskRunRow::getReviewTaskId, Function.identity()));
        Map<String, String> projectNames = projects.selectBatchIds(
                        visible.stream().map(TaskDto::project).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(ProjectEntity::getId, ProjectEntity::getName));

        return visible.stream()
                .map(task -> item(task, latestByTask.get(task.id()), projectNames.get(task.project())))
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingInt((ReviewableTaskDto item) -> RETRY.equals(item.action()) ? 0 : 1)
                        .thenComparing(ReviewableTaskDto::finishedAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(ReviewableTaskDto::taskId))
                .toList();
    }

    private ReviewableTaskDto item(TaskDto task, ReviewableTaskRunRow run, String projectName) {
        String action;
        if ("待验证".equals(task.status())) {
            action = ACCEPTANCE;
        } else if (!"已完成".equals(task.status()) && run != null && "failed".equals(run.getStatus())) {
            action = RETRY;
        } else {
            return null;
        }
        return new ReviewableTaskDto(task.id(), task.title(), task.project(),
                StringUtils.hasText(projectName) ? projectName : "未知项目", task.pr(), task.status(), action,
                run == null ? null : run.getRunId(), run == null ? null : run.getDeliveryStage(),
                run == null ? null : firstLine(run.getResultSummary()),
                run == null ? null : offset(run.getFinishedAt()));
    }

    private static String firstLine(String summary) {
        if (!StringUtils.hasText(summary)) return null;
        return summary.lines().findFirst().orElse(summary).trim();
    }

    private static String offset(LocalDateTime value) {
        return value == null ? null : value.atZone(ZONE).toOffsetDateTime().toString();
    }
}
