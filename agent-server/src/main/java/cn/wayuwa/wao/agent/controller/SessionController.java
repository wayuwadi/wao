package cn.wayuwa.wao.agent.controller;

import cn.wayuwa.wao.agent.auth.AgentSessionManager;
import cn.wayuwa.wao.agent.common.ApiResponse;
import cn.wayuwa.wao.agent.dto.AgentSessionDto;
import cn.wayuwa.wao.agent.service.SharedAccountService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/session")
public class SessionController {
    private final AgentSessionManager sessions;
    private final SharedAccountService accounts;

    public SessionController(AgentSessionManager sessions, SharedAccountService accounts) {
        this.sessions = sessions;
        this.accounts = accounts;
    }

    @GetMapping
    public ApiResponse<AgentSessionDto> session(HttpServletRequest request) {
        return ApiResponse.ok(accounts.session(sessions.currentAccountId(request)));
    }
}
