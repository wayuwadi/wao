package cn.wayuwa.wao.controller;

import java.nio.charset.StandardCharsets;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.SharedDocDto;
import cn.wayuwa.wao.dto.SharedTaskDto;
import cn.wayuwa.wao.service.ShareService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * 任务分享接口（公开只读）。
 *
 * <p>两种形态服务两类读者：{@code /api/share/tasks/{id}} 返回 JSON 给分享页渲染，
 * {@code /api/share/tasks/{id}.md} 直接返回 Markdown 纯文本 —— 后者可以整条粘给 AI，
 * AI 一次 GET 就拿到完整内容，不需要执行前端 JS。
 *
 * <p>两者都支持 {@code ?openOnly=true}：持续迭代的任务分享出去时过滤掉已完成的子任务。
 */
@RestController
@RequestMapping("/api/share")
public class ShareController {

    private final ShareService share;
    /** 公网根地址；留空则从当前请求推导（Nginx 已透传 X-Forwarded-Proto / Host）。 */
    private final String configuredBaseUrl;

    public ShareController(ShareService share, @Value("${wao.share.base-url:}") String configuredBaseUrl) {
        this.share = share;
        this.configuredBaseUrl = configuredBaseUrl;
    }

    @GetMapping("/tasks/{id}")
    public ApiResponse<SharedTaskDto> task(
            @PathVariable String id,
            @RequestParam(defaultValue = "false") boolean openOnly) {
        return ApiResponse.ok(share.shareTask(id, openOnly));
    }

    @GetMapping("/tasks/{id}/subs/{subId}")
    public ApiResponse<SharedTaskDto> sub(@PathVariable String id, @PathVariable String subId) {
        return ApiResponse.ok(share.shareSub(id, subId));
    }

    @GetMapping("/tasks/{id}.md")
    public ResponseEntity<byte[]> taskMarkdown(
            @PathVariable String id,
            @RequestParam(defaultValue = "false") boolean openOnly) {
        return markdown(share.toMarkdown(share.shareTask(id, openOnly), baseUrl()));
    }

    @GetMapping("/tasks/{id}/subs/{subId}.md")
    public ResponseEntity<byte[]> subMarkdown(@PathVariable String id, @PathVariable String subId) {
        return markdown(share.toMarkdown(share.shareSub(id, subId), baseUrl()));
    }

    /** 一次合并 AI Run 的公开只读 Markdown；任务边界和用户选择顺序由服务端持久关系保证。 */
    @GetMapping("/ai-runs/{id}.md")
    public ResponseEntity<byte[]> runMarkdown(
            @PathVariable String id,
            @RequestParam(defaultValue = "false") boolean openOnly) {
        return markdown(share.toRunMarkdown(id, openOnly, baseUrl()));
    }

    @GetMapping("/docs/{id}")
    public ApiResponse<SharedDocDto> doc(@PathVariable String id) {
        return ApiResponse.ok(share.shareDoc(id));
    }

    @GetMapping("/docs/{id}.md")
    public ResponseEntity<byte[]> docMarkdown(@PathVariable String id) {
        return markdown(share.toMarkdown(share.shareDoc(id), baseUrl()));
    }

    /**
     * Markdown 里的图片要用绝对地址，这里给出公网根地址。
     *
     * <p>优先用配置值（代理层不透传 forwarded 头时的兜底），否则按当前请求推导 —— 应用开启了
     * {@code forward-headers-strategy: framework}，云 Nginx 也透传了 X-Forwarded-*，
     * 所以推导出来的是 https://wao.wayuwa.cn 而不是容器内部地址。
     */
    private String baseUrl() {
        if (configuredBaseUrl != null && !configuredBaseUrl.isBlank()) {
            return configuredBaseUrl;
        }
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    }

    /** 显式声明 UTF-8：中文任务标题在默认字符集下会乱码。 */
    private static ResponseEntity<byte[]> markdown(String text) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
                .body(text.getBytes(StandardCharsets.UTF_8));
    }
}
