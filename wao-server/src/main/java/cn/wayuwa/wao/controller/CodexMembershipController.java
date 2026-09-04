package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.mapper.MemberMapper;
import cn.wayuwa.wao.service.CodexMembershipService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.*;

/** 已登录成员查看 Codex 会员信息，管理员维护账号共享日期。 */
@RestController
@RequestMapping("/api/codex-memberships")
public class CodexMembershipController {
    private final CodexMembershipService service;
    private final CurrentMember current;
    private final MemberMapper members;
    public CodexMembershipController(CodexMembershipService service, CurrentMember current, MemberMapper members) {
        this.service = service; this.current = current; this.members = members;
    }
    @GetMapping
    public ApiResponse<List<CodexMembershipService.AccountMembership>> list(HttpServletRequest http) {
        if (current.id(http) == null) throw new UnauthorizedException("请先登录");
        return ApiResponse.ok(service.list());
    }
    @PutMapping
    public ApiResponse<CodexMembershipService.Membership> save(@RequestBody CodexMembershipService.Membership request, HttpServletRequest http) {
        String id = current.id(http);
        var member = id == null ? null : members.selectById(id);
        if (member == null || !Set.of("管理员", "超级管理员").contains(member.getRole()))
            throw new UnauthorizedException("只有管理员可以维护账号会员日期");
        return ApiResponse.ok(service.save(request));
    }
}
