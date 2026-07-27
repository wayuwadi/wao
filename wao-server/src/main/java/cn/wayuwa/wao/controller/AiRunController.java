package cn.wayuwa.wao.controller;

import java.util.List;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.AiPairCodeDto;
import cn.wayuwa.wao.dto.AiProjectConfigDto;
import cn.wayuwa.wao.dto.AiRunDto;
import cn.wayuwa.wao.dto.AiRunnerClaimDto;
import cn.wayuwa.wao.dto.AiRunnerDto;
import cn.wayuwa.wao.dto.CompleteAiRunRequest;
import cn.wayuwa.wao.dto.ContinueAiRunRequest;
import cn.wayuwa.wao.dto.AiWorkflowDto;
import cn.wayuwa.wao.dto.CreateAiRunsRequest;
import cn.wayuwa.wao.dto.PairedAiRunnerDto;
import cn.wayuwa.wao.dto.PairAiRunnerRequest;
import cn.wayuwa.wao.dto.SaveAiProjectConfigRequest;
import cn.wayuwa.wao.dto.ReportAiRunProgressRequest;
import cn.wayuwa.wao.dto.RunnerScriptDto;
import cn.wayuwa.wao.dto.UpdateRunnerConcurrencyRequest;
import cn.wayuwa.wao.service.AiRunService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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

/** AI Run 页面配置、队列和本地执行节点协议。 */
@RestController
@RequestMapping("/api/ai")
public class AiRunController {

    private static final String RUNNER_TOKEN = "X-WAO-Runner-Token";

    private final AiRunService service;
    private final CurrentMember currentMember;

    public AiRunController(AiRunService service, CurrentMember currentMember) {
        this.service = service;
        this.currentMember = currentMember;
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

    @GetMapping("/runners")
    public ApiResponse<List<AiRunnerDto>> runners() {
        return ApiResponse.ok(service.listRunners());
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

    @PostMapping("/runs")
    public ApiResponse<List<AiRunDto>> enqueue(@Valid @RequestBody CreateAiRunsRequest request,
            HttpServletRequest http) {
        String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        return ApiResponse.ok(service.enqueue(request.taskIds(), request.subId(), request.engine(),
                request.runnerId(), currentMember.idOr(http, "unknown"), baseUrl));
    }

    @GetMapping("/runs")
    public ApiResponse<List<AiRunDto>> runs(@RequestParam(defaultValue = "false") boolean active) {
        return ApiResponse.ok(service.listRuns(active));
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
        return ApiResponse.ok(service.continueRun(runId, request.input(), currentMember.idOr(http, "unknown")));
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
            @RequestParam(required = false) String scriptHash) {
        return ApiResponse.ok(service.heartbeat(token, maxConcurrency, engines, scriptHash));
    }

    /** 执行电脑收到更新指令后拉取最新脚本。 */
    @GetMapping("/runner/script")
    public ApiResponse<RunnerScriptDto> runnerScript(@RequestHeader(RUNNER_TOKEN) String token) {
        return ApiResponse.ok(service.fetchRunnerScript(token));
    }

    @PostMapping("/runner/claim")
    public ApiResponse<AiRunnerClaimDto> claim(@RequestHeader(RUNNER_TOKEN) String token) {
        return ApiResponse.ok(service.claim(token));
    }

    @PostMapping("/runner/runs/{runId}/complete")
    public ApiResponse<AiRunDto> complete(@RequestHeader(RUNNER_TOKEN) String token,
            @PathVariable String runId, @RequestBody CompleteAiRunRequest request) {
        return ApiResponse.ok(service.complete(token, runId, request));
    }

    @PostMapping("/runner/runs/{runId}/progress")
    public ApiResponse<Void> progress(@RequestHeader(RUNNER_TOKEN) String token,
            @PathVariable String runId, @Valid @RequestBody ReportAiRunProgressRequest request) {
        service.reportProgress(token, runId, request);
        return ApiResponse.ok(null);
    }
}
