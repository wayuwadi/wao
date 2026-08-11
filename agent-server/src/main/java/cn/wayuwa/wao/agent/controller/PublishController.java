package cn.wayuwa.wao.agent.controller;

import cn.wayuwa.wao.agent.auth.AgentSessionManager;
import cn.wayuwa.wao.agent.common.ApiResponse;
import cn.wayuwa.wao.agent.common.UnauthorizedException;
import cn.wayuwa.wao.agent.dto.CreatePublishTaskRequest;
import cn.wayuwa.wao.agent.dto.MediaAssetDto;
import cn.wayuwa.wao.agent.dto.PublishCenterDto;
import cn.wayuwa.wao.agent.dto.PublishTaskDto;
import cn.wayuwa.wao.agent.dto.UpdatePublishTaskRequest;
import cn.wayuwa.wao.agent.service.MediaAssetService;
import cn.wayuwa.wao.agent.service.PublishTaskService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 发布中心接口。 */
@RestController
@RequestMapping("/api/agent/publish")
public class PublishController {
    private final AgentSessionManager sessions;
    private final PublishTaskService publishTasks;
    private final MediaAssetService mediaAssets;

    public PublishController(AgentSessionManager sessions, PublishTaskService publishTasks,
                             MediaAssetService mediaAssets) {
        this.sessions = sessions;
        this.publishTasks = publishTasks;
        this.mediaAssets = mediaAssets;
    }

    @GetMapping("/center")
    public ApiResponse<PublishCenterDto> center(HttpServletRequest request) {
        return ApiResponse.ok(publishTasks.center(accountId(request)));
    }

    @PostMapping("/tasks")
    public ApiResponse<PublishTaskDto> create(@Valid @RequestBody CreatePublishTaskRequest body,
                                              HttpServletRequest request) {
        return ApiResponse.ok(publishTasks.create(accountId(request), body));
    }

    @PostMapping("/tasks/{taskId}")
    public ApiResponse<PublishTaskDto> update(@PathVariable String taskId,
                                              @Valid @RequestBody UpdatePublishTaskRequest body,
                                              HttpServletRequest request) {
        return ApiResponse.ok(publishTasks.update(accountId(request), taskId, body));
    }

    @PostMapping("/tasks/{taskId}/submit")
    public ApiResponse<PublishTaskDto> submit(@PathVariable String taskId, HttpServletRequest request) {
        return ApiResponse.ok(publishTasks.submit(accountId(request), taskId));
    }

    @PostMapping("/media")
    public ApiResponse<MediaAssetDto> upload(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
        return ApiResponse.ok(mediaAssets.store(accountId(request), file));
    }

    private String accountId(HttpServletRequest request) {
        String accountId = sessions.currentAccountId(request);
        if (accountId == null) throw new UnauthorizedException("WAO 登录已过期，请重新登录后进入 Agent");
        return accountId;
    }
}
