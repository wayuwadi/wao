package cn.wayuwa.wao.agent.controller;

import cn.wayuwa.wao.agent.auth.AgentSessionManager;
import cn.wayuwa.wao.agent.common.ApiResponse;
import cn.wayuwa.wao.agent.common.BadRequestException;
import cn.wayuwa.wao.agent.common.UnauthorizedException;
import cn.wayuwa.wao.agent.domain.MediaAssetEntity;
import cn.wayuwa.wao.agent.dto.CompleteVideoExportRequest;
import cn.wayuwa.wao.agent.dto.MediaAssetDto;
import cn.wayuwa.wao.agent.dto.UpdateVideoProjectRequest;
import cn.wayuwa.wao.agent.dto.VideoProjectDto;
import cn.wayuwa.wao.agent.service.MediaAssetService;
import cn.wayuwa.wao.agent.service.VideoProjectService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 可控视频工作流：分镜草稿、真实 WebM 上传和鉴权回放。 */
@RestController
@RequestMapping("/api/agent/video-workflow")
public class VideoWorkflowController {
    private final AgentSessionManager sessions;
    private final VideoProjectService videos;
    private final MediaAssetService mediaAssets;

    public VideoWorkflowController(AgentSessionManager sessions, VideoProjectService videos,
                                   MediaAssetService mediaAssets) {
        this.sessions = sessions;
        this.videos = videos;
        this.mediaAssets = mediaAssets;
    }

    @GetMapping
    public ApiResponse<VideoProjectDto> project(HttpServletRequest request) {
        return ApiResponse.ok(videos.getOrCreate(accountId(request)));
    }

    @PostMapping
    public ApiResponse<VideoProjectDto> save(@Valid @RequestBody UpdateVideoProjectRequest body,
                                             HttpServletRequest request) {
        return ApiResponse.ok(videos.save(accountId(request), body));
    }

    /** 只接受浏览器合成的真实 WebM；普通图片和平台视频仍走发布中心上传。 */
    @PostMapping("/media")
    public ApiResponse<MediaAssetDto> upload(@RequestParam("file") MultipartFile file,
                                             HttpServletRequest request) {
        if (file == null || !"video/webm".equalsIgnoreCase(file.getContentType())) {
            throw new BadRequestException("视频工作流只接受浏览器合成的 WebM 文件");
        }
        try {
            byte[] signature = file.getInputStream().readNBytes(4);
            if (signature.length != 4 || signature[0] != 0x1A || signature[1] != 0x45
                    || signature[2] != (byte) 0xDF || signature[3] != (byte) 0xA3) {
                throw new BadRequestException("上传内容不是有效的 WebM 视频，不能只修改文件类型");
            }
        } catch (IOException ex) {
            throw new BadRequestException("读取 WebM 文件失败：" + ex.getMessage());
        }
        return ApiResponse.ok(mediaAssets.store(accountId(request), file));
    }

    @PostMapping("/export")
    public ApiResponse<VideoProjectDto> complete(@Valid @RequestBody CompleteVideoExportRequest body,
                                                 HttpServletRequest request) {
        return ApiResponse.ok(videos.completeExport(accountId(request), body));
    }

    /**
     * 播放或下载本工作区成片。单段 Range 足够浏览器顺序播放和拖动，且不会一次把大文件读入堆。
     */
    @GetMapping("/output")
    public void output(HttpServletRequest request, HttpServletResponse response) throws IOException {
        MediaAssetEntity media = videos.output(accountId(request));
        long size = media.getByteSize();
        long start = 0;
        long end = size - 1;
        String range = request.getHeader(HttpHeaders.RANGE);
        if (range != null && !range.isBlank()) {
            long[] parsed = parseRange(range, size);
            start = parsed[0];
            end = parsed[1];
            response.setStatus(HttpStatus.PARTIAL_CONTENT.value());
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + size);
        }
        long length = end - start + 1;
        response.setContentType(media.getContentType());
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(length));
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''"
                + URLEncoder.encode(media.getOriginalName(), StandardCharsets.UTF_8).replace("+", "%20"));
        try (RandomAccessFile source = new RandomAccessFile(media.getStoragePath(), "r")) {
            source.seek(start);
            byte[] buffer = new byte[64 * 1024];
            long remaining = length;
            while (remaining > 0) {
                int read = source.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) break;
                response.getOutputStream().write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    private long[] parseRange(String value, long size) {
        if (!value.startsWith("bytes=") || value.contains(",")) {
            throw new BadRequestException("只支持单段视频 Range 请求");
        }
        String[] parts = value.substring(6).split("-", -1);
        try {
            long start = parts[0].isBlank() ? 0 : Long.parseLong(parts[0]);
            long end = parts.length < 2 || parts[1].isBlank() ? size - 1 : Long.parseLong(parts[1]);
            if (start < 0 || end < start || start >= size) throw new NumberFormatException();
            return new long[] {start, Math.min(end, size - 1)};
        } catch (NumberFormatException ex) {
            throw new BadRequestException("视频 Range 超出文件范围");
        }
    }

    private String accountId(HttpServletRequest request) {
        String accountId = sessions.currentAccountId(request);
        if (accountId == null) throw new UnauthorizedException("WAO 登录已过期，请重新登录后进入 Agent");
        return accountId;
    }
}
