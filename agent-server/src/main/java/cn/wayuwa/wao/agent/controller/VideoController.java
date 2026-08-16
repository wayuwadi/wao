package cn.wayuwa.wao.agent.controller;

import cn.wayuwa.wao.agent.auth.AgentSessionManager;
import cn.wayuwa.wao.agent.common.ApiResponse;
import cn.wayuwa.wao.agent.common.UnauthorizedException;
import cn.wayuwa.wao.agent.dto.CreateVideoRequest;
import cn.wayuwa.wao.agent.dto.VideoCenterDto;
import cn.wayuwa.wao.agent.dto.VideoGenerationDto;
import cn.wayuwa.wao.agent.service.VideoGenerationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.MalformedURLException;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 真实视频生成、重试与鉴权播放接口。 */
@RestController
@RequestMapping("/api/agent/videos")
public class VideoController {
    private final AgentSessionManager sessions;
    private final VideoGenerationService videos;

    public VideoController(AgentSessionManager sessions, VideoGenerationService videos) {
        this.sessions = sessions;
        this.videos = videos;
    }

    @GetMapping
    public ApiResponse<VideoCenterDto> center(HttpServletRequest request) {
        return ApiResponse.ok(videos.center(accountId(request)));
    }

    @PostMapping
    public ApiResponse<VideoGenerationDto> create(@Valid @RequestBody CreateVideoRequest body,
                                                   HttpServletRequest request) {
        return ApiResponse.ok(videos.create(accountId(request), body));
    }

    @PostMapping("/{taskId}/retry")
    public ApiResponse<VideoGenerationDto> retry(@PathVariable String taskId, HttpServletRequest request) {
        return ApiResponse.ok(videos.retry(accountId(request), taskId));
    }

    @GetMapping("/{taskId}/content")
    public ResponseEntity<Resource> content(@PathVariable String taskId, HttpServletRequest request)
            throws MalformedURLException {
        VideoGenerationService.MediaFile file = videos.requireContent(accountId(request), taskId);
        Resource resource = new UrlResource(file.path().toUri());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.path().toFile().length())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=generated-video.mp4")
                .body(resource);
    }

    private String accountId(HttpServletRequest request) {
        String accountId = sessions.currentAccountId(request);
        if (accountId == null) throw new UnauthorizedException("WAO 登录已过期，请重新登录后进入 Agent");
        return accountId;
    }
}
