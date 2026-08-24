package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.dto.MemoryDocumentDto;
import cn.wayuwa.wao.dto.MemoryRevisionDto;
import cn.wayuwa.wao.dto.SaveMemoryRequest;
import cn.wayuwa.wao.service.UnifiedMemoryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** WAO 统一记忆中心的浏览、修订、审核与废止接口。 */
@RestController
@RequestMapping("/api")
public class UnifiedMemoryController {
    private final UnifiedMemoryService memories;
    private final CurrentMember currentMember;

    public UnifiedMemoryController(UnifiedMemoryService memories, CurrentMember currentMember) {
        this.memories = memories;
        this.currentMember = currentMember;
    }

    @GetMapping("/memories")
    public ApiResponse<List<MemoryDocumentDto>> list(@RequestParam String scopeType,
            @RequestParam(required = false) String scopeId, @RequestParam(required = false) String query,
            @RequestParam(required = false) String category, @RequestParam(required = false) String state,
            HttpServletRequest http) {
        return ApiResponse.ok(memories.list(member(http), scopeType, scopeId, query, category, state));
    }

    @GetMapping("/memories/{id}")
    public ApiResponse<MemoryDocumentDto> get(@PathVariable String id,
            @RequestParam(required = false) String revision, HttpServletRequest http) {
        return ApiResponse.ok(memories.get(member(http), id, revision));
    }

    @GetMapping("/memories/{id}/revisions")
    public ApiResponse<List<MemoryRevisionDto>> revisions(@PathVariable String id, HttpServletRequest http) {
        return ApiResponse.ok(memories.revisions(member(http), id));
    }

    @PostMapping("/memories")
    public ApiResponse<MemoryDocumentDto> create(@Valid @RequestBody SaveMemoryRequest body,
            HttpServletRequest http) {
        return ApiResponse.ok(memories.create(member(http), body, "human"));
    }

    @PostMapping("/memories/{id}/revisions")
    public ApiResponse<MemoryDocumentDto> revise(@PathVariable String id,
            @Valid @RequestBody SaveMemoryRequest body, HttpServletRequest http) {
        return ApiResponse.ok(memories.revise(member(http), id, body, "human"));
    }

    @PostMapping("/memory-revisions/{id}/publish")
    public ApiResponse<MemoryDocumentDto> publish(@PathVariable String id, HttpServletRequest http) {
        return ApiResponse.ok(memories.publish(member(http), id));
    }

    @PostMapping("/memory-revisions/{id}/reject")
    public ApiResponse<MemoryDocumentDto> reject(@PathVariable String id, HttpServletRequest http) {
        return ApiResponse.ok(memories.reject(member(http), id));
    }

    @PostMapping("/memories/{id}/archive")
    public ApiResponse<Void> archive(@PathVariable String id, HttpServletRequest http) {
        memories.archive(member(http), id);
        return ApiResponse.ok(null);
    }

    private String member(HttpServletRequest request) {
        String id = currentMember.id(request);
        if (!StringUtils.hasText(id)) throw new UnauthorizedException("登录成员不存在，请重新登录");
        return id;
    }
}
