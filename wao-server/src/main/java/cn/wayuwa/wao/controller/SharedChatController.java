package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.dto.CreateSharedChatMessageRequest;
import cn.wayuwa.wao.dto.SharedChatMessageDto;
import cn.wayuwa.wao.service.SharedChatService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 已登录成员共享的轻量聊天室。 */
@RestController
@RequestMapping("/api/chat")
public class SharedChatController {
    private final SharedChatService service;
    private final CurrentMember currentMember;

    public SharedChatController(SharedChatService service, CurrentMember currentMember) {
        this.service = service;
        this.currentMember = currentMember;
    }

    @GetMapping("/messages")
    public ApiResponse<List<SharedChatMessageDto>> list(
            @RequestParam(value = "after", required = false) String after, HttpServletRequest request) {
        return ApiResponse.ok(service.list(requireMember(request), after));
    }

    @PostMapping("/messages")
    public ApiResponse<SharedChatMessageDto> create(@RequestBody CreateSharedChatMessageRequest body,
            HttpServletRequest request) {
        return ApiResponse.ok(service.create(requireMember(request), body));
    }

    @PostMapping("/messages/read")
    public ApiResponse<Void> read(@RequestParam String through, HttpServletRequest request) {
        service.markRead(requireMember(request), through);
        return ApiResponse.ok(null);
    }

    @PostMapping("/messages/{id}/reactions/{emojiType}")
    public ApiResponse<SharedChatMessageDto> reaction(@PathVariable String id,
            @PathVariable String emojiType, HttpServletRequest request) {
        return ApiResponse.ok(service.toggleReaction(requireMember(request), id, emojiType));
    }

    private String requireMember(HttpServletRequest request) {
        String id = currentMember.id(request);
        if (id == null) throw new UnauthorizedException("登录已过期，请重新登录");
        return id;
    }
}
