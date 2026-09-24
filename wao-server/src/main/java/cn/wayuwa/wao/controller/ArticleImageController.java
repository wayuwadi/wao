package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.dto.ArticleImageJobDto;
import cn.wayuwa.wao.dto.ArticleImageRequest;
import cn.wayuwa.wao.service.ArticleImageService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 小记与文档共用的文章总览图入口。 */
@RestController
@RequestMapping("/api/assistant/article-images")
public class ArticleImageController {
    private final ArticleImageService service;
    private final CurrentMember currentMember;

    public ArticleImageController(ArticleImageService service, CurrentMember currentMember) {
        this.service = service;
        this.currentMember = currentMember;
    }

    @PostMapping
    public ApiResponse<ArticleImageJobDto> start(@Valid @RequestBody ArticleImageRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(service.start(requireMember(http), request));
    }

    @GetMapping("/{runId}")
    public ApiResponse<ArticleImageJobDto> get(@PathVariable String runId, HttpServletRequest http) {
        return ApiResponse.ok(service.get(requireMember(http), runId));
    }

    private String requireMember(HttpServletRequest http) {
        String memberId = currentMember.id(http);
        if (memberId == null) throw new UnauthorizedException("请先登录后再生成文章总览图");
        return memberId;
    }
}
