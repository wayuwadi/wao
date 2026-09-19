package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.CodexArchiveBatchRequest;
import cn.wayuwa.wao.service.CodexArchiveService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** 只接受有独立归档授权的设备 Bearer，不依赖 Cookie。 */
@RestController
@RequestMapping("/api/codex-archive")
public class CodexArchiveController {
    private final CodexArchiveService archives;
    public CodexArchiveController(CodexArchiveService archives) { this.archives = archives; }

    @GetMapping("/status")
    public ApiResponse<Map<String, String>> status(@RequestHeader(value = "Authorization", required = false) String auth) {
        return ApiResponse.ok(Map.of("memberId", archives.authenticate(auth), "kbId", CodexArchiveService.KB_ID,
                "protocol", "1", "visibility", "existing-knowledge-library"));
    }

    @PostMapping("/batches")
    public ApiResponse<Map<String, Object>> append(@RequestHeader(value = "Authorization", required = false) String auth,
            @Valid @RequestBody CodexArchiveBatchRequest batch) {
        return ApiResponse.ok(archives.append(archives.authenticate(auth), batch));
    }
}
