package cn.wayuwa.wao.service;

import cn.wayuwa.wao.dto.AiRunDto;
import cn.wayuwa.wao.dto.AiWorkflowDto;
import cn.wayuwa.wao.dto.RequestMetricEndpointDto;
import cn.wayuwa.wao.dto.RequestMetricOverviewDto;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 用真实浏览器请求指标生成每日一次的 WAO 性能治理 AI Run。 */
@Service
public class HttpPerformanceAuditService {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(HttpPerformanceAuditService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int WINDOW_HOURS = 24;
    private static final int ENDPOINT_LIMIT = 20;

    private final HttpRequestMetricService metrics;
    private final AiRunService runs;
    private final ProjectService projects;
    private final TaskService tasks;
    private final String publicBaseUrl;

    public HttpPerformanceAuditService(HttpRequestMetricService metrics, AiRunService runs,
            ProjectService projects, TaskService tasks,
            @Value("${wao.share.base-url:}") String publicBaseUrl) {
        this.metrics = metrics;
        this.runs = runs;
        this.projects = projects;
        this.tasks = tasks;
        this.publicBaseUrl = StringUtils.hasText(publicBaseUrl) ? publicBaseUrl.trim() : null;
    }

    /**
     * 检查最近 24 小时的真实请求指标，并为当天创建至多一条性能治理运行。
     *
     * <p>没有任何真实指标时不创建空任务；同名日任务已存在时幂等跳过。工作流、负责人和执行电脑
     * 都取现有 WAO 项目配置，不硬编码环境 id。
     *
     * @return 新建的运行；无指标或当天已经派发时为空
     */
    @Transactional
    public Optional<AiRunDto> runNightlyAudit() {
        RequestMetricOverviewDto overview = metrics.systemOverview(WINDOW_HOURS);
        if (overview.totalRequests() == 0) {
            LOG.info("夜间 HTTP 性能巡检跳过：最近 24 小时没有真实请求指标");
            return Optional.empty();
        }
        AiWorkflowDto workflow = waoWorkflow();
        String title = "HTTP 请求性能夜间巡检 " + LocalDate.now(ZONE);
        if (tasks.existsByProjectAndTitle(workflow.projectId(), title)) {
            LOG.info("夜间 HTTP 性能巡检跳过：{} 已经派发", title);
            return Optional.empty();
        }
        if (publicBaseUrl == null) {
            throw new IllegalStateException("未配置公网任务分享地址，无法创建夜间性能巡检 AI Run");
        }
        String ownerId = projects.get(workflow.projectId()).owner();
        AiRunDto run = runs.launchScheduledGoal(workflow.projectId(), goal(title, overview), ownerId,
                publicBaseUrl, workflow.config().preferredRunnerId());
        LOG.info("夜间 HTTP 性能巡检已派发：{}，运行 {}", title, run.id());
        return Optional.of(run);
    }

    private AiWorkflowDto waoWorkflow() {
        return runs.listWorkflows().stream()
                .filter(workflow -> workflow.configured() && !workflow.projectDeleted())
                .filter(workflow -> workflow.config().enabled())
                .filter(HttpPerformanceAuditService::isWaoProject)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("没有已启用的 Wa All in One AI 工作流，无法派发夜间性能巡检"));
    }

    private static boolean isWaoProject(AiWorkflowDto workflow) {
        if ("Wa All in One".equalsIgnoreCase(workflow.projectName())) return true;
        String repo = workflow.config().repoUrl().toLowerCase(Locale.ROOT).replaceAll("/+$", "");
        return repo.endsWith("/wao") || repo.endsWith("/wao.git");
    }

    private static String goal(String title, RequestMetricOverviewDto overview) {
        StringBuilder endpoints = new StringBuilder();
        List<RequestMetricEndpointDto> items = overview.endpoints().stream().limit(ENDPOINT_LIMIT).toList();
        for (RequestMetricEndpointDto endpoint : items) {
            endpoints.append("- ").append(endpoint.method()).append(' ').append(endpoint.path())
                    .append("：").append(endpoint.requestCount()).append(" 次，平均 ")
                    .append(endpoint.averageDurationMs()).append(" ms，P95 ")
                    .append(endpoint.p95DurationMs()).append(" ms，慢请求 ")
                    .append(endpoint.slowCount()).append(" 次，失败 ")
                    .append(endpoint.failedCount()).append(" 次\n");
        }
        return title + "\n\n"
                + "这是 WAO 根据浏览器端到端 HTTP 监控自动创建的每日性能治理任务。指标包含用户网络、"
                + "云 Nginx、Gateway 和服务端完整等待时间，不含请求参数、正文、凭据或成员身份。\n\n"
                + "## 最近 24 小时实测\n\n"
                + "- 总请求：" + overview.totalRequests() + " 次\n"
                + "- 平均耗时：" + overview.averageDurationMs() + " ms\n"
                + "- P95：" + overview.p95DurationMs() + " ms\n"
                + "- 慢请求（不少于 1000 ms）：" + overview.slowRequests() + " 次\n"
                + "- 失败请求：" + overview.failedRequests() + " 次\n\n"
                + "## 优先关注接口\n\n" + endpoints + "\n"
                + "请先结合当前规格、代码、查询链路和可取得的真实证据判断是否仍有值得实施的优化。"
                + "若需要优化，优先修复高频慢请求的根因，补充核心逻辑测试，并用真实用户链路对比改动前后；"
                + "不得用缓存掩盖权限错误、返回演示数据或降低数据一致性。若指标已经健康，或现有证据不足以支持"
                + "安全改动，不要为了产生提交而修改代码，请在结果摘要中写清无需优化或暂不处理的真实依据。";
    }
}
