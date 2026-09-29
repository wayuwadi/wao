package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.dto.MemoryDocumentDto;
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

/** 个人决策的专属接口；复用版本存储，不开放已下线的通用记忆中心。 */
@RestController
@RequestMapping("/api/decisions")
public class DecisionController {
    private final UnifiedMemoryService decisions;
    private final CurrentMember currentMember;

    public DecisionController(UnifiedMemoryService decisions, CurrentMember currentMember) {
        this.decisions = decisions;
        this.currentMember = currentMember;
    }

    /** 列出本人已发布决策。 */
    @GetMapping
    public ApiResponse<List<MemoryDocumentDto>> list(@RequestParam(required = false) String query,
            HttpServletRequest http) {
        String member = member(http);
        return ApiResponse.ok(decisions.list(member, "personal", member, query, "decision", "active"));
    }

    /** 读取本人决策及指定版本。 */
    @GetMapping("/{id}")
    public ApiResponse<MemoryDocumentDto> get(@PathVariable String id,
            @RequestParam(required = false) String revision, HttpServletRequest http) {
        return ApiResponse.ok(ownedDecision(member(http), id, revision));
    }

    /** 新增本人决策。 */
    @PostMapping
    public ApiResponse<MemoryDocumentDto> create(@Valid @RequestBody SaveMemoryRequest body,
            HttpServletRequest http) {
        String member = member(http);
        return ApiResponse.ok(decisions.create(member, scoped(member, body), "human"));
    }

    /** 修订本人决策，保留原有版本冲突保护。 */
    @PostMapping("/{id}/revisions")
    public ApiResponse<MemoryDocumentDto> revise(@PathVariable String id,
            @Valid @RequestBody SaveMemoryRequest body, HttpServletRequest http) {
        String member = member(http);
        ownedDecision(member, id, null);
        return ApiResponse.ok(decisions.revise(member, id, scoped(member, body), "human"));
    }

    /** 废止本人决策。 */
    @PostMapping("/{id}/archive")
    public ApiResponse<Void> archive(@PathVariable String id, HttpServletRequest http) {
        String member = member(http);
        ownedDecision(member, id, null);
        decisions.archive(member, id);
        return ApiResponse.ok(null);
    }

    private MemoryDocumentDto ownedDecision(String member, String id, String revision) {
        MemoryDocumentDto result = decisions.get(member, id, revision);
        if (!"personal".equals(result.scopeType()) || !"decision".equals(result.category())
                || !member.equals(result.scopeId())) throw new NotFoundException("决策不存在");
        return result;
    }

    private SaveMemoryRequest scoped(String member, SaveMemoryRequest input) {
        return new SaveMemoryRequest("personal", member, "decision", input.title(), input.content(),
                input.changeSummary(), input.evidenceLinks(), input.expiresAt(), input.baseRevisionId());
    }

    private String member(HttpServletRequest request) {
        String id = currentMember.id(request);
        if (!StringUtils.hasText(id)) throw new UnauthorizedException("登录成员不存在，请重新登录");
        return id;
    }
}
