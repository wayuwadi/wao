package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.common.ApiResponse;
import java.util.Map;
import cn.wayuwa.wao.service.ArticleImageService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 本地 Runner 的总览图回传接口，复用领取令牌与图床。 */
@RestController
@RequestMapping("/api/ai/runner/assistant-v2")
public class ArticleImageRunnerController {
    private final ArticleImageService service;

    public ArticleImageRunnerController(ArticleImageService service) {
        this.service = service;
    }

    @PostMapping(value = "/{runId}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, String>> upload(@RequestHeader("X-WAO-Runner-Token") String token,
            @PathVariable String runId, @RequestParam String claimToken, @RequestParam MultipartFile image) {
        return ApiResponse.ok(Map.of("url", service.upload(token, runId, claimToken, image)));
    }
}
