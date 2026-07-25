package cn.wayuwa.wao.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import cn.wayuwa.wao.auth.AuthSessionManager;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.domain.FileBlobEntity;
import cn.wayuwa.wao.dto.FileItemDto;
import cn.wayuwa.wao.service.DownloadTicketService;
import cn.wayuwa.wao.service.FileService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 真实文件模块接口。
 *
 * <p>下载不再是任人无限次拉取的公开直链：外部访问要先在确认页上过一道人机校验，换一张
 * 一次性票据（{@link DownloadTicketService}）；已登录的自己人凭会话直接下，不用答题。
 * 上传和删除一律需要登录会话。
 */
@RestController
@RequestMapping("/api/files")
public class FileController {
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

    /** 取一道人机校验题（公开）。 */
    @PostMapping("/{id}/challenge")
    public ApiResponse<DownloadTicketService.ChallengeDto> challenge(@PathVariable String id) {
        files.meta(id);
        return ApiResponse.ok(tickets.issueChallenge(id));
    }

    /** 用答案换一张一次性下载票据（公开）。 */
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

    @DeleteMapping("/{id}") public ApiResponse<Void> delete(@PathVariable String id) { files.delete(id); return ApiResponse.ok(null); }

    /** 兑换票据的请求体。 */
    public record TicketRequest(String challengeId, String answer) { }
}
