package cn.wayuwa.wao.controller;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import cn.wayuwa.wao.auth.AuthSessionManager;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.domain.FileBlobEntity;
import cn.wayuwa.wao.dto.FileItemDto;
import cn.wayuwa.wao.service.DownloadTicketService;
import cn.wayuwa.wao.service.FileService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 真实文件模块接口。
 *
 * <p>下载不再是任人无限次拉取的公开直链：外部访问要先在确认页点击，按设置完成人机校验，换一张
 * 一次性票据（{@link DownloadTicketService}）；已登录的自己人凭会话直接下，不用答题。
 * 上传和删除一律需要登录会话。
 */
@RestController
@RequestMapping("/api/files")
public class FileController {
    private static final Pattern BYTE_RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");
    private final FileService files;
    private final CurrentMember currentMember;
    private final DownloadTicketService tickets;
    private final AuthSessionManager sessions;

    public FileController(FileService files, CurrentMember currentMember, DownloadTicketService tickets,
            AuthSessionManager sessions) {
        this.files = files;
        this.currentMember = currentMember;
        this.tickets = tickets;
        this.sessions = sessions;
    }

    @GetMapping public ApiResponse<List<FileItemDto>> list() { return ApiResponse.ok(files.list()); }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<FileItemDto> upload(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
        return ApiResponse.ok(files.upload(file, currentMember.id(request)));
    }

    /** 确认页要展示的文件信息（公开；只有文件名与大小，不含正文）。 */
    @GetMapping("/{id}/meta")
    public ApiResponse<FileItemDto> meta(@PathVariable String id) {
        return ApiResponse.ok(files.meta(id));
    }

    /** 取一次公开下载挑战；算术题可关闭，一次性票据不可关闭。 */
    @PostMapping("/{id}/challenge")
    public ApiResponse<DownloadTicketService.ChallengeDto> challenge(@PathVariable String id) {
        files.meta(id);
        return ApiResponse.ok(tickets.issueChallenge(id));
    }

    /** 消费挑战并按需校验答案，换一张一次性下载票据（公开）。 */
    @PostMapping("/{id}/ticket")
    public ApiResponse<Map<String, String>> ticket(@PathVariable String id, @RequestBody TicketRequest request) {
        files.meta(id);
        return ApiResponse.ok(Map.of("ticket", tickets.redeem(id, request.challengeId(), request.answer())));
    }

    /**
     * 下载文件正文。
     *
     * <p>凭一次性票据或本人登录会话放行；两者都没有就不给字节 —— 这就是挡住「拿到链接无限刷」的那道门。
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable String id,
            @RequestParam(required = false) String ticket, HttpServletRequest request) {
        boolean loggedIn = sessions.currentAccountId(request) != null;
        if (!loggedIn && !tickets.consume(id, ticket)) {
            throw new UnauthorizedException("下载链接需要先完成验证，请回到下载页重试");
        }
        FileBlobEntity entity = files.raw(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(entity.getContentType()))
                .header("Content-Disposition", ContentDisposition.attachment()
                        .filename(entity.getName(), StandardCharsets.UTF_8).build().toString())
                .body(entity.getContent());
    }

    /**
     * 向已登录的 WAO 页面提供可拖动进度的音频流。
     *
     * <p>该路径不在 {@link cn.wayuwa.wao.auth.AuthInterceptor} 的公开白名单中，因此必须有有效会话；
     * 同时只接受音频 MIME 或常见音频扩展名，避免把任意上传文件以内联方式暴露。
     */
    @GetMapping("/{id}/stream")
    public ResponseEntity<byte[]> stream(@PathVariable String id,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {
        FileBlobEntity entity = files.raw(id);
        MediaType mediaType = audioMediaType(entity);
        byte[] content = entity.getContent();
        if (rangeHeader == null || rangeHeader.isBlank()) {
            return streamResponse(entity, mediaType, content, null);
        }
        try {
            ByteRange range = parseRange(rangeHeader, content.length);
            byte[] part = Arrays.copyOfRange(content, (int) range.start(), (int) range.end() + 1);
            return streamResponse(entity, mediaType, part, range);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + content.length)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .build();
        }
    }

    @DeleteMapping("/{id}") public ApiResponse<Void> delete(@PathVariable String id) { files.delete(id); return ApiResponse.ok(null); }

    private static ResponseEntity<byte[]> streamResponse(FileBlobEntity entity, MediaType mediaType, byte[] body,
            ByteRange range) {
        ResponseEntity.BodyBuilder response = range == null
                ? ResponseEntity.ok()
                : ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                        .header(HttpHeaders.CONTENT_RANGE,
                                "bytes " + range.start() + "-" + range.end() + "/" + range.total());
        return response
                .contentType(mediaType)
                .contentLength(body.length)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(entity.getName(), StandardCharsets.UTF_8).build().toString())
                .body(body);
    }

    private static MediaType audioMediaType(FileBlobEntity entity) {
        String contentType = entity.getContentType() == null ? "" : entity.getContentType().trim().toLowerCase(Locale.ROOT);
        if (contentType.startsWith("audio/")) {
            try {
                return MediaType.parseMediaType(contentType);
            } catch (IllegalArgumentException ignored) {
                // 上传端给出损坏 MIME 时继续按扩展名识别，不能把播放器变成 500。
            }
        }
        String name = entity.getName() == null ? "" : entity.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".mp3")) return MediaType.parseMediaType("audio/mpeg");
        if (name.endsWith(".m4a")) return MediaType.parseMediaType("audio/mp4");
        if (name.endsWith(".aac")) return MediaType.parseMediaType("audio/aac");
        if (name.endsWith(".wav")) return MediaType.parseMediaType("audio/wav");
        if (name.endsWith(".ogg") || name.endsWith(".oga") || name.endsWith(".opus")) {
            return MediaType.parseMediaType("audio/ogg");
        }
        if (name.endsWith(".flac")) return MediaType.parseMediaType("audio/flac");
        if (name.endsWith(".webm")) return MediaType.parseMediaType("audio/webm");
        throw new BadRequestException("该文件不是可播放的音频");
    }

    private static ByteRange parseRange(String header, long total) {
        Matcher matcher = BYTE_RANGE.matcher(header.trim());
        if (!matcher.matches() || total <= 0 || (matcher.group(1).isEmpty() && matcher.group(2).isEmpty())) {
            throw new IllegalArgumentException("invalid range");
        }
        long start;
        long end;
        if (matcher.group(1).isEmpty()) {
            long suffix = Long.parseLong(matcher.group(2));
            if (suffix <= 0) throw new IllegalArgumentException("invalid suffix range");
            start = Math.max(0, total - suffix);
            end = total - 1;
        } else {
            start = Long.parseLong(matcher.group(1));
            end = matcher.group(2).isEmpty() ? total - 1 : Math.min(Long.parseLong(matcher.group(2)), total - 1);
        }
        if (start < 0 || start >= total || end < start) throw new IllegalArgumentException("range out of bounds");
        return new ByteRange(start, end, total);
    }

    private record ByteRange(long start, long end, long total) { }

    /** 兑换票据的请求体。 */
    public record TicketRequest(String challengeId, String answer) { }
}
