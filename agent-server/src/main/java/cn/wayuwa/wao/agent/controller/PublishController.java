package cn.wayuwa.wao.agent.controller;

import cn.wayuwa.wao.agent.auth.AgentSessionManager;
import cn.wayuwa.wao.agent.common.ApiResponse;
import cn.wayuwa.wao.agent.common.UnauthorizedException;
import cn.wayuwa.wao.agent.dto.CreatePublishTaskRequest;
import cn.wayuwa.wao.agent.dto.CreateContentGroupRequest;
import cn.wayuwa.wao.agent.dto.MediaAssetDto;
import cn.wayuwa.wao.agent.dto.PublishCenterDto;
import cn.wayuwa.wao.agent.dto.PublishTaskDto;
import cn.wayuwa.wao.agent.dto.UpdatePublicationDataRequest;
import cn.wayuwa.wao.agent.dto.UpdatePublishTaskRequest;
import cn.wayuwa.wao.agent.service.MediaAssetService;
import cn.wayuwa.wao.agent.service.PublishTaskService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    /** 用同一标题与参考内容原子创建抖音、小红书和公众号三端生成任务。 */
    @PostMapping("/content-groups")
    public ApiResponse<List<PublishTaskDto>> createContentGroup(
            @Valid @RequestBody CreateContentGroupRequest body, HttpServletRequest request) {
        return ApiResponse.ok(publishTasks.createContentGroup(accountId(request), body));
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

    @PostMapping("/tasks/{taskId}/retry-generation")
    public ApiResponse<PublishTaskDto> retryGeneration(@PathVariable String taskId,
                                                       HttpServletRequest request) {
        return ApiResponse.ok(publishTasks.retryGeneration(accountId(request), taskId));
    }

    @PostMapping("/tasks/{taskId}/mark-published")
    public ApiResponse<PublishTaskDto> markPublished(@PathVariable String taskId,
                                                     HttpServletRequest request) {
        return ApiResponse.ok(publishTasks.markPublished(accountId(request), taskId));
    }

    @PostMapping("/tasks/{taskId}/publication-data")
    public ApiResponse<PublishTaskDto> updatePublicationData(
            @PathVariable String taskId, @Valid @RequestBody UpdatePublicationDataRequest body,
            HttpServletRequest request) {
        return ApiResponse.ok(publishTasks.updatePublicationData(accountId(request), taskId, body));
    }

    @PostMapping("/media")
    public ApiResponse<MediaAssetDto> upload(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
        return ApiResponse.ok(mediaAssets.store(accountId(request), file));
    }

    @GetMapping("/media")
    public ApiResponse<List<MediaAssetDto>> media(HttpServletRequest request) {
        return ApiResponse.ok(mediaAssets.list(accountId(request)));
    }

    /** 在当前登录工作区内以内联方式返回真实素材，供编辑器实时预览。 */
    @GetMapping("/media/{mediaAssetId}/content")
    public ResponseEntity<Resource> mediaContent(@PathVariable String mediaAssetId, HttpServletRequest request) {
        var asset = mediaAssets.requireLocal(accountId(request), mediaAssetId);
        Resource resource = new FileSystemResource(asset.getStoragePath());
        MediaType type;
        try {
            type = MediaType.parseMediaType(asset.getContentType());
        } catch (IllegalArgumentException ignored) {
            type = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(asset.getOriginalName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                .body(resource);
    }

    private String accountId(HttpServletRequest request) {
        String accountId = sessions.currentAccountId(request);
        if (accountId == null) throw new UnauthorizedException("WAO 登录已过期，请重新登录后进入 Agent");
        return accountId;
    }
}
