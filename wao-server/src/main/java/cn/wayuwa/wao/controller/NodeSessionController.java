package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.NodeSessionBridge;
import cn.wayuwa.wao.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;

/** 主站CSRF、节点票据签发与一次性兑换；所有响应禁止缓存。 */
@RestController
@RequestMapping("/api/edge")
@ConditionalOnProperty(name = "wao.edge.session-enabled", havingValue = "true")
public class NodeSessionController {
    private final NodeSessionBridge bridge;
    private final JdbcTemplate jdbc;
    public NodeSessionController(NodeSessionBridge bridge, JdbcTemplate jdbc) { this.bridge = bridge; this.jdbc = jdbc; }
    public record IssueRequest(@NotBlank @Size(max = 128) String targetOrigin) {}
    public record ExchangeRequest(@NotBlank @Size(max = 128) String ticket) {}

    @GetMapping("/ready")
    public ApiResponse<Map<String, Integer>> ready(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        try {
            Integer writable = jdbc.queryForObject(
                    "SELECT IF(@@global.read_only=0 AND @@global.super_read_only=0,1,0)", Integer.class);
            if (!Integer.valueOf(1).equals(writable)) {
                response.setStatus(503);
                return ApiResponse.fail(50300, "权威数据库未就绪");
            }
        } catch (DataAccessException unavailable) {
            response.setStatus(503);
            return ApiResponse.fail(50300, "权威数据库未就绪");
        }
        return ApiResponse.ok(Map.of("protocol", 1));
    }

    @GetMapping("/session")
    public ApiResponse<Map<String, Integer>> session(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        if (request.getSession(false) == null || bridge.currentAccount(request, request.getSession(false)) == null)
            throw new cn.wayuwa.wao.common.UnauthorizedException("节点认证已过期");
        return ApiResponse.ok(Map.of("protocol", 1));
    }

    @GetMapping("/csrf")
    public ApiResponse<Map<String, String>> csrf(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.ok(Map.of("csrf", bridge.csrf(request)));
    }

    @PostMapping("/tickets")
    public ApiResponse<NodeSessionBridge.TicketGrant> issue(@Valid @RequestBody IssueRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.ok(bridge.issue(request, body.targetOrigin()));
    }

    @PostMapping("/exchange")
    public ApiResponse<Void> exchange(@Valid @RequestBody ExchangeRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        bridge.exchange(request, body.ticket());
        return ApiResponse.ok(null);
    }
}
