package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Per-member smart-list choices, stored independently so different lists never overwrite each other. */
@RestController
@RequestMapping("/api/preferences/smart-list/items")
public class SmartListVisibilityController {
    static final String PREFIX = "tsv1:";
    private final MemberPreferenceMapper mapper;
    private final CurrentMember member;

    public SmartListVisibilityController(MemberPreferenceMapper mapper, CurrentMember member) {
        this.mapper = mapper;
        this.member = member;
    }

    private String owner(HttpServletRequest request) {
        String id = member.id(request);
        if (id == null || id.isBlank()) throw new UnauthorizedException("请登录后管理智能清单显示设置");
        return id;
    }

    @GetMapping
    public ApiResponse<Map<String, String>> list(HttpServletRequest request) {
        Map<String, String> result = new LinkedHashMap<>();
        mapper.findRange(owner(request), PREFIX, PREFIX + "\uffff").forEach(entry -> {
            if (entry.getPrefKey().startsWith(PREFIX))
                result.put(entry.getPrefKey().substring(PREFIX.length()), entry.getPrefValue());
        });
        return ApiResponse.ok(result);
    }

    public record Change(Boolean visible, String expected) {}

    @PutMapping("/{projectId}")
    public ApiResponse<Void> change(@PathVariable String projectId, @RequestBody Change change,
                                    HttpServletRequest request) {
        if (!projectId.matches("[A-Za-z0-9_-]{1,59}") || change.visible() == null
                || !("".equals(change.expected()) || "0".equals(change.expected()) || "1".equals(change.expected())))
            throw new BadRequestException("智能清单显示设置格式错误");
        String owner = owner(request), key = PREFIX + projectId;
        String desired = change.visible() ? "1" : "0";
        String current = mapper.find(owner, key);
        if (desired.equals(current)) return ApiResponse.ok(null); // safe retry after an uncertain response
        if (!change.expected().equals(current == null ? "" : current))
            throw new ConflictException("智能清单显示设置已在其他设备修改");
        int updated = current == null ? mapper.insertIfAbsent(owner, key, desired)
                : mapper.compareAndSet(owner, key, current, desired);
        if (updated != 1) throw new ConflictException("智能清单显示设置已在其他设备修改");
        return ApiResponse.ok(null);
    }
}
