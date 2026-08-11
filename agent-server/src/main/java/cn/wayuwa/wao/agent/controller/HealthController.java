package cn.wayuwa.wao.agent.controller;

import cn.wayuwa.wao.agent.common.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    @GetMapping("/api/agent/health")
    public ApiResponse<Map<String, String>> health() {
        return ApiResponse.ok(Map.of("service", "wao-agent-server", "status", "UP"));
    }
}
