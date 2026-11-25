package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.service.AgentMemoryService;
import cn.wayuwa.wao.service.AgentMemoryService.Entry;
import cn.wayuwa.wao.service.AgentMemoryService.Input;
import cn.wayuwa.wao.service.AgentMemoryService.Page;
import cn.wayuwa.wao.service.AgentMemoryService.Review;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent-memories")
public class AgentMemoryController {
    private final AgentMemoryService memories;
    private final CurrentMember current;

    public AgentMemoryController(AgentMemoryService memories, CurrentMember current) {
        this.memories = memories;
        this.current = current;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Page>> list(@RequestParam(defaultValue = "draft") String state,
            @RequestParam(required = false) String query, @RequestParam(required = false) String project,
            @RequestParam(required = false) String source, @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest http) {
        return privateResponse(memories.list(member(http), state, query, project, source, offset));
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<ApiResponse<List<Entry>>> history(@PathVariable String id, HttpServletRequest http) {
        return privateResponse(memories.history(member(http), id));
    }

    @PostMapping
    public ApiResponse<Entry> propose(@RequestBody Input input, HttpServletRequest http) {
        return ApiResponse.ok(memories.propose(member(http), input));
    }

    @PostMapping("/{id}/review")
    public ApiResponse<Entry> review(@PathVariable String id, @RequestBody Review review, HttpServletRequest http) {
        return ApiResponse.ok(memories.review(member(http), id, review));
    }

    private static <T> ResponseEntity<ApiResponse<T>> privateResponse(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(ApiResponse.ok(body));
    }

    private String member(HttpServletRequest http) {
        String member = current.id(http);
        if (member == null || member.isBlank()) throw new UnauthorizedException("请先登录 WAO");
        return member;
    }
}
