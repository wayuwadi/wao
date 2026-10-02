package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.common.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 旧执行节点得到明确退役状态，不再领取任务、配对或访问集中凭据。 */
@RestController
@RequestMapping({"/api/ai/runner", "/api/ai/runners"})
public class RetiredRunnerController {
    @RequestMapping({"", "/**"})
    public ResponseEntity<ApiResponse<Void>> retired() {
        return ResponseEntity.status(410).body(ApiResponse.fail(41000, "WAO Runner 已下线"));
    }
}
