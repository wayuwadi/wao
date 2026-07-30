package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.AssistantChatRequest;
import cn.wayuwa.wao.dto.AssistantReplyDto;
import cn.wayuwa.wao.service.AiAssistantService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** AI 助手对话接口：支持流式 SSE 与一次性返回两种模式。 */
@RestController
@RequestMapping("/api/assistant")
public class AiAssistantController {

    private final AiAssistantService service;

    public AiAssistantController(AiAssistantService service) {
        this.service = service;
    }

    /** 是否已配置 API key；前端据此决定是否展示配置引导。 */
    @GetMapping("/status")
    public ApiResponse<Boolean> status() {
        return ApiResponse.ok(service.configured());
    }

    /** 流式对话：通过 SSE 把每个 token 实时推送给前端。 */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody AssistantChatRequest request) {
        SseEmitter emitter = new SseEmitter(60_000L);
        // 异步调用，避免占用 Tomcat 线程。
        Thread.startVirtualThread(() -> service.chatStream(request, emitter));
        return emitter;
    }

    /** 一次性返回完整回复；适合网络环境不支持 SSE 的场景。 */
    @PostMapping("/chat")
    public ApiResponse<AssistantReplyDto> chat(@Valid @RequestBody AssistantChatRequest request) {
        String content = service.chatOnce(request);
        String status = service.configured() ? "ok" : "no_config";
        return ApiResponse.ok(new AssistantReplyDto(java.util.UUID.randomUUID().toString(), content, status));
    }
}
