package cn.wayuwa.wao.service;

import cn.wayuwa.wao.domain.AutomationJobEntity;
import cn.wayuwa.wao.domain.ProjectEntity;
import cn.wayuwa.wao.dto.AiRunDto;
import cn.wayuwa.wao.dto.AiWorkflowDto;
import cn.wayuwa.wao.dto.HttpPerformanceInspectionConfigDto;
import cn.wayuwa.wao.dto.FunctionHealthOverviewDto;
import cn.wayuwa.wao.dto.RequestMetricEndpointDto;
import cn.wayuwa.wao.dto.RequestMetricOverviewDto;
import cn.wayuwa.wao.mapper.ProjectMapper;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 把项目负责人的真实 HTTP 指标快照交给 WAO 项目的开发 AI Run。 */
@Component
public class HttpPerformanceAiRunJobHandler implements JobHandler {

    static final String HANDLER_NAME = "http-performance-ai-run";
    static final String PROJECT_NAME = "Wa All in One";
    private final HttpRequestMetricService metrics;
    private final AiRunService aiRuns;
    private final ProjectMapper projects;
    private final HttpPerformanceInspectionConfigService inspectionConfig;
    private final FunctionHealthService functionHealth;
    private final FeishuTaskNotifier notifier;
    private final String publicBaseUrl;

    public HttpPerformanceAiRunJobHandler(HttpRequestMetricService metrics, AiRunService aiRuns,
            ProjectMapper projects, HttpPerformanceInspectionConfigService inspectionConfig,
            FunctionHealthService functionHealth, FeishuTaskNotifier notifier,
            @Value("${wao.share.base-url:}") String configuredBaseUrl) {
        this.metrics = metrics;
        this.aiRuns = aiRuns;
        this.projects = projects;
        this.inspectionConfig = inspectionConfig;
        this.functionHealth = functionHealth;
        this.notifier = notifier;
        this.publicBaseUrl = StringUtils.hasText(configuredBaseUrl)
                ? configuredBaseUrl.trim().replaceFirst("/+$", "") : null;
    }

    /** {@inheritDoc} */
    @Override
    public String name() {
        return HANDLER_NAME;
    }

    /** 手动触发时执行与夜间调度相同的真实巡检。 */
    @Override
    public String execute() {
        return executeInspection(null, null).message();
    }

    /**
     * 读取最近 24 小时指标；窗口非空时原子创建 Task 与 AI Run。
     *
     * @param job 自动化任务配置
     * @param scheduledFireAt 本次计划触发时间；只用于自动化审计，指标窗口始终以执行时刻为准
     * @return 自动化历史中的有界执行结果
     */
    @Override
    public String execute(AutomationJobEntity job, LocalDateTime scheduledFireAt) {
        return executeInspection(job, scheduledFireAt).message();
    }

    /** 同时把本次创建的 AI Run id 交给自动化历史持久化。 */
    @Override
    public JobExecutionResult executeWithMetadata(AutomationJobEntity job, LocalDateTime scheduledFireAt) {
        return executeInspection(job, scheduledFireAt);
    }

    private JobExecutionResult executeInspection(AutomationJobEntity job, LocalDateTime scheduledFireAt) {
        if (publicBaseUrl == null) {
            throw new IllegalStateException("未配置 WAO_SHARE_BASE_URL，无法生成公开任务地址");
        }
        AiWorkflowDto workflow = aiRuns.listWorkflows().stream()
                .filter(candidate -> PROJECT_NAME.equals(candidate.projectName()))
                .filter(candidate -> !candidate.projectDeleted() && candidate.config() != null
                        && candidate.config().enabled())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Wa All in One 尚未启用 AI 工作流"));
        ProjectEntity project = projects.selectById(workflow.projectId());
        if (project == null || !StringUtils.hasText(project.getOwnerId())) {
            throw new IllegalStateException("Wa All in One 项目或负责人不存在");
        }

        HttpPerformanceInspectionConfigDto config = inspectionConfig.get();
        RequestMetricOverviewDto snapshot = metrics.overview(project.getOwnerId(), config.windowHours());
        FunctionHealthOverviewDto health = functionHealth.overview(project.getOwnerId());
        if (snapshot.totalRequests() == 0 && health.recentOpens() == 0) {
            return JobExecutionResult.messageOnly(
                    "跳过 · 当前没有真实 HTTP 指标或功能使用记录");
        }

        AiRunDto run = aiRuns.launchInspectionGoal(
                project.getId(), prompt(snapshot, health, config), project.getOwnerId(), publicBaseUrl);
        boolean notificationFailed = config.notificationWebhookId() != null
                && !notifier.notifyHttpPerformanceInspectionStarted(run.taskId(), project.getId(), run.taskTitle(),
                        PROJECT_NAME, config.windowHours(), config.topN(), config.maxEndpointsPerRun(),
                        config.notificationWebhookId());
        return new JobExecutionResult(
                "成功 · 已创建 AI Run " + run.id() + (notificationFailed ? "；启动通知未发出" : ""),
                run.id());
    }

    private static String prompt(RequestMetricOverviewDto snapshot, FunctionHealthOverviewDto health,
            HttpPerformanceInspectionConfigDto config) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("# HTTP 请求性能夜间巡检 · ").append(snapshot.generatedAt().toLocalDate()).append("\n\n")
                .append("请使用下面由 WAO 浏览器监控真实采集的最近 ").append(config.windowHours())
                .append(" 小时聚合数据，检查当前仓库实现，")
                .append("定位仍有用户影响的性能根因；有可靠优化时直接完成代码、测试、PR、部署与线上复验。")
                .append("如果瓶颈来自网络或缺少可验证的代码根因，不要伪造优化或收益，明确说明证据和跳过原因。\n\n")
                .append("本轮还要从功能产品角度检查可用度、操作路径和功能重叠。只能依据下面真实使用快照与仓库实现；")
                .append("没有足够证据时只给出需要继续采集的事实，不猜测用户点击或伪造 AI 建议。\n\n")
                .append("## 本次真实指标快照\n\n")
                .append("- 生成时间：").append(snapshot.generatedAt()).append("（Asia/Shanghai）\n")
                .append("- 请求数：").append(snapshot.totalRequests()).append("\n")
                .append("- 平均耗时：").append(snapshot.averageDurationMs()).append(" ms\n")
                .append("- P95：").append(snapshot.p95DurationMs()).append(" ms\n")
                .append("- 慢请求（>= 1000 ms）：").append(snapshot.slowRequests()).append("\n")
                .append("- 失败请求：").append(snapshot.failedRequests()).append("\n\n")
                .append("- Top N 候选接口：").append(config.topN()).append("\n")
                .append("- 本次最多治理接口数：").append(config.maxEndpointsPerRun()).append("\n\n")
                .append("## 优先接口\n\n")
                .append("| 方法 | 路径 | 次数 | 平均 ms | P95 ms | 慢请求 | 失败 |\n")
                .append("| --- | --- | ---: | ---: | ---: | ---: | ---: |\n");
        snapshot.endpoints().stream()
                .sorted(Comparator.comparingInt(RequestMetricEndpointDto::averageDurationMs).reversed())
                .limit(config.topN())
                .forEach(endpoint -> prompt.append("| ").append(endpoint.method()).append(" | `")
                        .append(endpoint.path().replace("|", "%7C")).append("` | ")
                        .append(endpoint.requestCount()).append(" | ")
                        .append(endpoint.averageDurationMs()).append(" | ")
                        .append(endpoint.p95DurationMs()).append(" | ")
                        .append(endpoint.slowCount()).append(" | ")
                        .append(endpoint.failedCount()).append(" |\n"));
        prompt.append("\n## 功能可用度快照（最近 ").append(health.windowDays()).append(" 天）\n\n")
                .append("- 功能打开总次数：").append(health.recentOpens()).append("\n\n")
                .append("| 功能 id | 满意分 | 打开次数 | 使用占比 | 当前提示 |\n")
                .append("| --- | ---: | ---: | ---: | --- |\n");
        health.functions().stream()
                .sorted(Comparator.comparingLong(cn.wayuwa.wao.dto.FunctionHealthItemDto::recentOpens).reversed())
                .forEach(item -> prompt.append("| `").append(item.featureId()).append("` | ")
                        .append(item.score()).append(" | ").append(item.recentOpens()).append(" | ")
                        .append(String.format(Locale.ROOT, "%.1f%%", item.recentShare())).append(" | ")
                        .append(item.insight().replace("|", "｜")).append(" |\n"));
        prompt.append("\n请从上述候选中最多治理 ").append(config.maxEndpointsPerRun())
                .append(" 个确有代码根因的接口。验收必须保持接口结果、权限隔离和用户链路不变，并用下一次真实监控窗口对比；")
                .append("不得把编译通过或未经测量的百分比当作线上提速证据。功能分析需核对路由、页面和真实业务边界，")
                .append("指出低频功能是入口问题、路径问题、边界重叠还是样本不足；只有证据充分且属于同一任务边界时才直接优化。\n\n")
                .append("本次 HTML 报告除 AI Run 通用内容外，必须逐项写明：实际优化了哪些接口、各自根因、")
                .append("是否已经彻底解决及仍存边界、后续如何规避、对后续开发有指导作用的经验沉淀。")
                .append("没有可靠代码优化时也要在报告中如实说明证据和跳过原因，不能伪造已解决结论。");
        return prompt.toString();
    }
}
