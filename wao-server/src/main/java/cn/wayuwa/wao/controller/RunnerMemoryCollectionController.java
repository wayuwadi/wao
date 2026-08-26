package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.domain.AiRunnerNodeEntity;
import cn.wayuwa.wao.dto.MemoryDocumentDto;
import cn.wayuwa.wao.dto.RunnerMemoryReportRequest;
import cn.wayuwa.wao.service.AiRunService;
import cn.wayuwa.wao.service.RunnerMemoryCollectionService;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 本地 Runner 单向上传机器/应用记忆报告；原始 Codex 文件不进入接口。 */
@RestController
@RequestMapping("/api/ai/runner/memory-reports")
public class RunnerMemoryCollectionController {
    private static final String RUNNER_TOKEN = "X-WAO-Runner-Token";
    private final AiRunService runs;
    private final RunnerMemoryCollectionService collections;

    public RunnerMemoryCollectionController(AiRunService runs, RunnerMemoryCollectionService collections) {
        this.runs = runs;
        this.collections = collections;
    }

    @PostMapping
    public ApiResponse<List<MemoryDocumentDto>> collect(@RequestHeader(RUNNER_TOKEN) String token,
            @RequestBody(required = false) RunnerMemoryReportRequest request) {
        AiRunnerNodeEntity runner = runs.authenticateRunner(token);
        return ApiResponse.ok(collections.collect(runner, request));
    }
}
