package cn.wayuwa.wao.controller;

import java.util.List;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.dto.AssistantChatRequest;
import cn.wayuwa.wao.dto.AiCapabilityDto;
import cn.wayuwa.wao.dto.AssistantConversationDetailDto;
import cn.wayuwa.wao.dto.AssistantConversationDto;
import cn.wayuwa.wao.dto.AssistantReplyDto;
import cn.wayuwa.wao.dto.AssistantStatusDto;
import cn.wayuwa.wao.dto.AssistantSourceOptionsDto;
import cn.wayuwa.wao.dto.PolishTextDto;
import cn.wayuwa.wao.dto.PolishTextRequest;
import cn.wayuwa.wao.service.AiAssistantAssetService;
import cn.wayuwa.wao.service.AiAssistantConversationService;
import cn.wayuwa.wao.service.AiAssistantService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** AI 助手对话接口：支持流式 SSE 与一次性返回两种模式。 */
@RestController
@RequestMapping("/api/assistant")
public class AiAssistantController {

    @org.springframework.beans.factory.annotation.Autowired
    private cn.wayuwa.wao.service.AssistantStreamExecutor streams;

    private final AiAssistantService service;
    private final AiAssistantConversationService conversationService;
    private final AiAssistantAssetService assetService;
    private final CurrentMember currentMember;

    public AiAssistantController(AiAssistantService service,
            AiAssistantConversationService conversationService, AiAssistantAssetService assetService,
            CurrentMember currentMember) {
        this.service = service;
        this.conversationService = conversationService;
        this.assetService = assetService;
        this.currentMember = currentMember;
    }

    /** 是否存在可提供页面问答的在线本地 Codex。 */
    @GetMapping("/status")
    public ApiResponse<AssistantStatusDto> status(
            @RequestParam(required = false) String capabilityId) {
        return ApiResponse.ok(service.status(capabilityId));
    }

    /** 返回可选择的本地 Runner 与集中 API/Token 配置，不暴露凭据。 */
    @GetMapping("/capabilities")
    public ApiResponse<List<AiCapabilityDto>> capabilities() {
        return ApiResponse.ok(service.capabilities());
    }

    /** 把文档中明确选中的文字交给只读本地 Codex 润色，不写入助手聊天历史。 */
    @PostMapping("/polish")
    public ApiResponse<PolishTextDto> polish(@Valid @RequestBody PolishTextRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(new PolishTextDto(
                service.polishText(request.text(), currentMember.idOr(http, "unknown"))));
    }

    /** 返回助手可检索资产的群聊、发言人、日期和项目筛选选项。 */
    @GetMapping("/source-options")
    public ApiResponse<AssistantSourceOptionsDto> sourceOptions() {
        return ApiResponse.ok(assetService.sourceOptions());
    }

    /** 返回当前成员的 AI 助手历史会话。 */
    @GetMapping("/conversations")
    public ApiResponse<List<AssistantConversationDto>> conversations(HttpServletRequest http) {
        return ApiResponse.ok(conversationService.list(currentMember.idOr(http, "unknown")));
    }

    /** 新建一个空白 AI 助手会话。 */
    @PostMapping("/conversations")
    public ApiResponse<AssistantConversationDto> createConversation(HttpServletRequest http) {
        return ApiResponse.ok(conversationService.create(currentMember.idOr(http, "unknown")));
    }

    /** 读取当前成员的一条会话及消息。 */
    @GetMapping("/conversations/{conversationId}")
    public ApiResponse<AssistantConversationDetailDto> conversation(
            @PathVariable String conversationId, HttpServletRequest http) {
        return ApiResponse.ok(conversationService.detail(
                conversationId, currentMember.idOr(http, "unknown")));
    }

    /** 删除当前成员的一条非执行中会话。 */
    @DeleteMapping("/conversations/{conversationId}")
    public ApiResponse<Void> deleteConversation(@PathVariable String conversationId, HttpServletRequest http) {
        conversationService.delete(conversationId, currentMember.idOr(http, "unknown"));
        return ApiResponse.ok(null);
    }

    /** 流式对话：通过 SSE 把每个 token 实时推送给前端。 */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody AssistantChatRequest request, HttpServletRequest http) {
        SseEmitter emitter = new SseEmitter(310_000L);
        String memberId = currentMember.idOr(http, "unknown");
        return streams.submit(emitter, () -> service.chatStream(request, memberId, emitter));
    }

    /** 一次性返回完整回复；适合网络环境不支持 SSE 的场景。 */
    @PostMapping("/chat")
    public ApiResponse<AssistantReplyDto> chat(@Valid @RequestBody AssistantChatRequest request,
            HttpServletRequest http) {
        String content = service.chatOnce(request, currentMember.idOr(http, "unknown"));
        String status = service.status(request.aiCapabilityId()).configured() ? "ok" : "no_config";
        return ApiResponse.ok(new AssistantReplyDto(java.util.UUID.randomUUID().toString(), content, status));
    }
}
