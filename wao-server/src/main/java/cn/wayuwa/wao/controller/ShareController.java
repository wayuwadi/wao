package cn.wayuwa.wao.controller;

import java.nio.charset.StandardCharsets;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.SharedTaskDto;
import cn.wayuwa.wao.service.ShareService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务分享接口（公开只读）。
 *
 * <p>两种形态服务两类读者：{@code /api/share/tasks/{id}} 返回 JSON 给分享页渲染，
 * {@code /api/share/tasks/{id}.md} 直接返回 Markdown 纯文本 —— 后者可以整条粘给 AI，
 * AI 一次 GET 就拿到完整内容，不需要执行前端 JS。
 */
@RestController
@RequestMapping("/api/share")
public class ShareController {

    private final ShareService share;

    public ShareController(ShareService share) {
        this.share = share;
    }

    @GetMapping("/tasks/{id}")
    public ApiResponse<SharedTaskDto> task(@PathVariable String id) {
        return ApiResponse.ok(share.shareTask(id));
    }

    @GetMapping("/tasks/{id}/subs/{subId}")
    public ApiResponse<SharedTaskDto> sub(@PathVariable String id, @PathVariable String subId) {
        return ApiResponse.ok(share.shareSub(id, subId));
    }

    @GetMapping("/tasks/{id}.md")
    public ResponseEntity<byte[]> taskMarkdown(@PathVariable String id) {
        return markdown(share.toMarkdown(share.shareTask(id)));
    }

    @GetMapping("/tasks/{id}/subs/{subId}.md")
    public ResponseEntity<byte[]> subMarkdown(@PathVariable String id, @PathVariable String subId) {
        return markdown(share.toMarkdown(share.shareSub(id, subId)));
    }

    /** 显式声明 UTF-8：中文任务标题在默认字符集下会乱码。 */
    private static ResponseEntity<byte[]> markdown(String text) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
                .body(text.getBytes(StandardCharsets.UTF_8));
    }
}
