package cn.wayuwa.wao.controller;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.AiAccountCredentialsDto;
import cn.wayuwa.wao.dto.AiAssistantClaimDto;
import cn.wayuwa.wao.dto.AiPairCodeDto;
import cn.wayuwa.wao.dto.AiProjectConfigDto;
import cn.wayuwa.wao.dto.AiRunDto;
import cn.wayuwa.wao.dto.AiRunImageContent;
import cn.wayuwa.wao.dto.AiRunPageDto;
import cn.wayuwa.wao.dto.AiRunnerClaimDto;
import cn.wayuwa.wao.dto.AiRunnerDto;
import cn.wayuwa.wao.dto.AiRunnerLogDto;
import cn.wayuwa.wao.dto.CompleteAiRunRequest;
import cn.wayuwa.wao.dto.CompleteAiAssistantRunRequest;
import cn.wayuwa.wao.dto.ContinueAiRunRequest;
import cn.wayuwa.wao.dto.HostedPageDto;
import cn.wayuwa.wao.dto.AiWorkflowDto;
import cn.wayuwa.wao.dto.AiEvolutionOverviewDto;
import cn.wayuwa.wao.dto.AiEvolutionSuggestionDto;
import cn.wayuwa.wao.dto.LaunchEvolutionSuggestionRequest;
import cn.wayuwa.wao.dto.CreateAiRunsRequest;
import cn.wayuwa.wao.dto.LaunchAutonomousGoalRequest;
import cn.wayuwa.wao.dto.PairedAiRunnerDto;
import cn.wayuwa.wao.dto.PairAiRunnerRequest;
import cn.wayuwa.wao.dto.SaveAiProjectConfigRequest;
import cn.wayuwa.wao.dto.SaveAiEvolutionScheduleRequest;
import cn.wayuwa.wao.dto.ReportAiRunProgressRequest;
import cn.wayuwa.wao.dto.RunnerScriptDto;
import cn.wayuwa.wao.dto.RunnerHeartbeatRequest;
import cn.wayuwa.wao.dto.UpdateRunnerConcurrencyRequest;
import cn.wayuwa.wao.dto.UpdateRunnerWakeConfigRequest;
import cn.wayuwa.wao.dto.WakeRunnerResultDto;
import cn.wayuwa.wao.service.AiAccountService;
import cn.wayuwa.wao.service.AiAssistantService;
import cn.wayuwa.wao.service.AiRunService;
import cn.wayuwa.wao.service.AiRunnerWakeService;
import cn.wayuwa.wao.service.AiRunnerLifecycleService;
import cn.wayuwa.wao.service.AiEvolutionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.multipart.MultipartFile;

/** AI Run 页面配置、队列和本地执行节点协议。 */
@RestController
@RequestMapping("/api/ai")
public class AiRunController {

    private static final String RUNNER_TOKEN = "X-WAO-Runner-Token";

    private final AiRunService service;
    private final AiAccountService aiAccountService;
    private final AiAssistantService assistantService;
    private final AiRunnerWakeService wakeService;
    private final AiRunnerLifecycleService lifecycleService;
    private final CurrentMember currentMember;
    private final AiEvolutionService evolutionService;

    public AiRunController(AiRunService service, AiAccountService aiAccountService,
            AiAssistantService assistantService,
            AiRunnerWakeService wakeService, AiRunnerLifecycleService lifecycleService,
            CurrentMember currentMember, AiEvolutionService evolutionService) {
        this.service = service;
        this.aiAccountService = aiAccountService;
        this.assistantService = assistantService;
        this.wakeService = wakeService;
        this.lifecycleService = lifecycleService;
        this.currentMember = currentMember;
        this.evolutionService = evolutionService;
    }

    @GetMapping("/projects/{projectId}/config")
    public ApiResponse<AiProjectConfigDto> config(@PathVariable String projectId) {
        return ApiResponse.ok(service.getConfig(projectId));
    }

    @PutMapping("/projects/{projectId}/config")
    public ApiResponse<AiProjectConfigDto> saveConfig(@PathVariable String projectId,
            @RequestBody SaveAiProjectConfigRequest request) {
        return ApiResponse.ok(service.saveConfig(projectId, request));
    }

    @GetMapping("/workflows")
    public ApiResponse<List<AiWorkflowDto>> workflows() {
        return ApiResponse.ok(service.listWorkflows());
    }

    /** 删除工作流配置；历史运行记录保留，项目仍存在时可重新接入。 */
    @DeleteMapping("/workflows/{projectId}")
    public ApiResponse<Void> deleteWorkflow(@PathVariable String projectId) {
        service.deleteWorkflow(projectId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/runners")
    public ApiResponse<List<AiRunnerDto>> runners() {
        return ApiResponse.ok(service.listRunners());
    }

    /** 按需读取一台执行电脑最近上报的诊断日志，不把日志塞进列表轮询。 */
    @GetMapping("/runners/{runnerId}/log")
    public ApiResponse<AiRunnerLogDto> runnerLog(@PathVariable String runnerId) {
        return ApiResponse.ok(service.runnerLog(runnerId));
    }

    @PostMapping("/runners/pair-code")
    public ApiResponse<AiPairCodeDto> pairCode() {
        return ApiResponse.ok(service.createPairCode());
    }

    /** 一键把服务端打包的最新 Runner 脚本下发给所有执行电脑。 */
    @PostMapping("/runners/update-script")
    public ApiResponse<List<AiRunnerDto>> updateRunnerScript() {
        return ApiResponse.ok(service.requestScriptUpdate());
    }

    @PutMapping("/runners/{runnerId}/max-concurrency")
    public ApiResponse<AiRunnerDto> updateRunnerMaxConcurrency(@PathVariable String runnerId,
            @Valid @RequestBody UpdateRunnerConcurrencyRequest request) {
        return ApiResponse.ok(service.updateRunnerMaxConcurrency(runnerId, request.maxConcurrency()));
    }

    @PutMapping("/runners/{runnerId}/wake-config")
    public ApiResponse<AiRunnerDto> updateRunnerWakeConfig(@PathVariable String runnerId,
            @Valid @RequestBody UpdateRunnerWakeConfigRequest request) {
        wakeService.updateConfig(runnerId, request);
        return ApiResponse.ok(service.listRunners().stream()
                .filter(runner -> runner.id().equals(runnerId)).findFirst().orElseThrow());
    }

    /** 解除一台执行电脑的接入；返回解除后剩下的电脑列表。 */
    @DeleteMapping("/runners/{runnerId}")
    public ApiResponse<List<AiRunnerDto>> removeRunner(@PathVariable String runnerId) {
        lifecycleService.notifyRemoved(service.removeRunner(runnerId));
        return ApiResponse.ok(service.listRunners());
    }

    @PostMapping("/runners/{runnerId}/wake")
    public ApiResponse<WakeRunnerResultDto> wakeRunner(@PathVariable String runnerId) {
        return ApiResponse.ok(wakeService.wake(runnerId));
    }

    @PostMapping("/runs")
    public ApiResponse<List<AiRunDto>> enqueue(@Valid @RequestBody CreateAiRunsRequest request,
            HttpServletRequest http) {
        String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        return ApiResponse.ok(service.enqueue(request.taskIds(), request.subId(), request.engine(),
                request.runnerId(), request.accountId(), currentMember.idOr(http, "unknown"), baseUrl));
    }

    /** 把一句目标转成任务，并交给用户本次选择的执行电脑或自动分配。 */
    @PostMapping("/goals")
    public ApiResponse<AiRunDto> launchGoal(@Valid @RequestBody LaunchAutonomousGoalRequest request,
            HttpServletRequest http) {
        String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        return ApiResponse.ok(service.launchGoal(request.projectId(), request.goal(), currentMember.id(http), baseUrl,
                request.runnerId(), null));
    }

    /** 首页额度驱动的自主进化状态与可直接执行的建议清单。 */
    @GetMapping("/evolution")
    public ApiResponse<AiEvolutionOverviewDto> evolution() {
        return ApiResponse.ok(evolutionService.overview());
    }

    /** 保存自主进化触发间隔与单次任务数，并让新配置立即进入调度检查。 */
    @PutMapping("/evolution/schedule")
    public ApiResponse<AiEvolutionOverviewDto> saveEvolutionSchedule(
            @Valid @RequestBody SaveAiEvolutionScheduleRequest request) {
        evolutionService.saveSchedule(request.intervalHours(), request.batchSize());
        return ApiResponse.ok(evolutionService.overview());
    }

    /** 从真实知识库与飞书同步归档重新挖掘最多五条候选任务。 */
    @PostMapping("/evolution/suggestions/refresh")
    public ApiResponse<List<AiEvolutionSuggestionDto>> refreshEvolutionSuggestions() {
        return ApiResponse.ok(evolutionService.refreshSuggestions());
    }

    /** 一键把一条已持久化的真实资产建议交给选中的 AI 工作流。 */
    @PostMapping("/evolution/suggestions/{suggestionId}/launch")
    public ApiResponse<AiRunDto> launchSuggestion(@PathVariable String suggestionId,
            @Valid @RequestBody LaunchEvolutionSuggestionRequest request, HttpServletRequest http) {
        String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        return ApiResponse.ok(evolutionService.launchSuggestion(suggestionId, request.projectId(),
                currentMember.id(http), baseUrl));
    }

    @GetMapping("/runs")
    public ApiResponse<List<AiRunDto>> runs(@RequestParam(defaultValue = "false") boolean active) {
        return ApiResponse.ok(service.listRuns(active));
    }

    /** 已结束运行的完整分页历史；首页只取八条，用户主动打开时才读取。 */
    @GetMapping("/runs/history")
    public ApiResponse<AiRunPageDto> runHistory(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.listRunHistory(page, size));
    }

    /** 兼容预览历史任务中遗留的 Base64 图片，不让编码正文进入运行列表响应。 */
    @GetMapping("/runs/{runId}/images/{imageIndex}")
    public ResponseEntity<byte[]> runPreviewImage(@PathVariable String runId, @PathVariable int imageIndex) {
        AiRunImageContent image = service.runPreviewImage(runId, imageIndex);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .body(image.content());
    }

    /** 基于一次失败运行重新排队，AI 会拿到上次的报错继续修。 */
    @PostMapping("/runs/{runId}/rerun")
    public ApiResponse<AiRunDto> rerun(@PathVariable String runId, HttpServletRequest http) {
        String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        return ApiResponse.ok(service.rerun(runId, currentMember.idOr(http, "unknown"), baseUrl));
    }

    @PostMapping("/runs/{runId}/continue")
    public ApiResponse<AiRunDto> continueRun(@PathVariable String runId,
            @Valid @RequestBody ContinueAiRunRequest request, HttpServletRequest http) {
        String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        return ApiResponse.ok(service.continueRun(runId, request.input(), currentMember.idOr(http, "unknown"),
                baseUrl));
    }

    /** 暂停尚未结束的 AI 运行；执行节点会在下一次取消检查时停止本地 CLI。 */
    @PostMapping("/runs/{runId}/pause")
    public ApiResponse<AiRunDto> pause(@PathVariable String runId) {
        return ApiResponse.ok(service.pause(runId));
    }

    @GetMapping("/tasks/{taskId}/runs")
    public ApiResponse<List<AiRunDto>> taskRuns(@PathVariable String taskId) {
        return ApiResponse.ok(service.listTaskRuns(taskId));
    }

    @PostMapping("/runner/pair")
    public ApiResponse<PairedAiRunnerDto> pair(@Valid @RequestBody PairAiRunnerRequest request) {
        return ApiResponse.ok(service.pair(request));
    }

    @PostMapping("/runner/heartbeat")
    public ApiResponse<AiRunnerDto> heartbeat(@RequestHeader(RUNNER_TOKEN) String token,
            @RequestParam(required = false) Integer maxConcurrency,
            @RequestParam(required = false) String engines,
            @RequestParam(required = false) String scriptHash,
            @RequestParam(required = false) String wakeMac,
            @RequestBody(required = false) RunnerHeartbeatRequest request,
            HttpServletRequest http) {
        AiRunnerDto runner = service.heartbeat(token, maxConcurrency, engines, scriptHash,
                wakeMac, clientAddress(http), request == null ? null : request.logTail(),
                request == null ? null : request.codexQuota(),
                request == null ? null : request.codexQuotaError());
        lifecycleService.markOnline(runner.id());
        return ApiResponse.ok(runner);
    }

    /** 执行电脑收到更新指令后拉取最新脚本。 */
    @GetMapping("/runner/script")
    public ApiResponse<RunnerScriptDto> runnerScript(@RequestHeader(RUNNER_TOKEN) String token) {
        return ApiResponse.ok(service.fetchRunnerScript(token));
    }

    /** 执行电脑拉取启用中的 AI 账号明文凭据，用于写入本机 CLI 配置文件。 */
    @GetMapping("/runner/accounts")
    public ApiResponse<List<AiAccountCredentialsDto>> runnerAccounts(@RequestHeader(RUNNER_TOKEN) String token) {
        service.authenticateRunner(token);
        return ApiResponse.ok(aiAccountService.enabledCredentials());
    }

    @PostMapping("/runner/claim")
    public ApiResponse<AiRunnerClaimDto> claim(@RequestHeader(RUNNER_TOKEN) String token) {
        return ApiResponse.ok(service.claim(token));
    }

    /** 空闲的本地 Codex 节点领取一条页面问答。 */
    @PostMapping("/runner/assistant/claim")
    public ApiResponse<AiAssistantClaimDto> claimAssistant(@RequestHeader(RUNNER_TOKEN) String token) {
        return ApiResponse.ok(assistantService.claim(token));
    }

    /** 本地 Codex 节点回传页面问答结果。 */
    @PostMapping("/runner/assistant/{runId}/complete")
    public ApiResponse<Void> completeAssistant(@RequestHeader(RUNNER_TOKEN) String token,
            @PathVariable String runId, @Valid @RequestBody CompleteAiAssistantRunRequest request) {
        assistantService.complete(token, runId, request);
        return ApiResponse.ok(null);
    }

    @PostMapping("/runner/runs/{runId}/complete")
    public ApiResponse<AiRunDto> complete(@RequestHeader(RUNNER_TOKEN) String token,
            @PathVariable String runId, @RequestBody CompleteAiRunRequest request) {
        return ApiResponse.ok(service.complete(token, runId, request));
    }

    /** Runner 上传本次运行额外生成的单文件 HTML 报告。 */
    @PostMapping(value = "/runner/runs/{runId}/report", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<HostedPageDto> report(@RequestHeader(RUNNER_TOKEN) String token,
            @PathVariable String runId, @RequestParam MultipartFile html) {
        return ApiResponse.ok(service.saveReport(token, runId, html));
    }

    /** Runner 在执行期间轮询取消状态，以便可靠终止本地 AI CLI 子进程。 */
    @GetMapping("/runner/runs/{runId}/control")
    public ApiResponse<Map<String, Boolean>> control(@RequestHeader(RUNNER_TOKEN) String token,
            @PathVariable String runId) {
        return ApiResponse.ok(Map.of("cancelled", service.cancelled(token, runId)));
    }

    @PostMapping("/runner/runs/{runId}/progress")
    public ApiResponse<Void> progress(@RequestHeader(RUNNER_TOKEN) String token,
            @PathVariable String runId, @Valid @RequestBody ReportAiRunProgressRequest request) {
        service.reportProgress(token, runId, request);
        return ApiResponse.ok(null);
    }

    private String clientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded == null || forwarded.isBlank()
                ? request.getRemoteAddr()
                : forwarded.split(",", 2)[0].trim();
    }
}
