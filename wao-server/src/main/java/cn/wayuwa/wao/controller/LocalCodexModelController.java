package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.service.LocalCodexModelService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/** 登录成员读取本机可见模型，不暴露主机路径或账号。 */
@RestController
@RequestMapping("/api/assistant/codex-models")
public class LocalCodexModelController {
    private final LocalCodexModelService service;
    private final CurrentMember member;
    public LocalCodexModelController(LocalCodexModelService service, CurrentMember member) {
        this.service = service; this.member = member;
    }
    /** 获取定期刷新的真实模型目录。 */
    @GetMapping
    public ApiResponse<LocalCodexModelService.Catalog> get(HttpServletRequest request) {
        if (member.id(request) == null) throw new UnauthorizedException("请先登录后再读取模型目录");
        return ApiResponse.ok(service.catalog());
    }
}
