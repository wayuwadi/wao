package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.AuthSessionManager;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.ForbiddenException;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.service.AccountAuthService;
import cn.wayuwa.wao.service.DeepSeekBudgetService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/** 全局付费能力策略只能由管理员读取和修改。 */
@RestController
@RequestMapping("/api/settings/deepseek-policy")
public class DeepSeekPolicyController {
    private final DeepSeekBudgetService budget;
    private final AccountAuthService auth;
    private final AuthSessionManager sessions;

    public DeepSeekPolicyController(DeepSeekBudgetService budget, AccountAuthService auth, AuthSessionManager sessions) {
        this.budget = budget;
        this.auth = auth;
        this.sessions = sessions;
    }

    @GetMapping
    public ApiResponse<DeepSeekBudgetService.Snapshot> get(HttpServletRequest request) {
        requireAdmin(request);
        return ApiResponse.ok(budget.snapshot());
    }

    @PutMapping
    public ApiResponse<DeepSeekBudgetService.Snapshot> save(HttpServletRequest request,
            @RequestBody DeepSeekBudgetService.Policy policy) {
        requireAdmin(request);
        return ApiResponse.ok(budget.save(policy));
    }

    private void requireAdmin(HttpServletRequest request) {
        String account = sessions.currentAccountId(request);
        if (account == null) throw new UnauthorizedException("请先登录");
        String role = auth.session(account).member().role();
        if (!"管理员".equals(role) && !"超级管理员".equals(role)) throw new ForbiddenException("只有管理员可以管理 DeepSeek 使用范围和预算");
    }
}
