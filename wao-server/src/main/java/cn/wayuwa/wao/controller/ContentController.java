package cn.wayuwa.wao.controller;

import java.util.List;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.service.ContentService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 内容 / 参考域只读接口（知识库、文档、自动化、文件、图床、密码库、日历、日程、建议、目标）。 */
@RestController
@RequestMapping("/api")
public class ContentController {

    private final ContentService content;

    public ContentController(ContentService content) {
        this.content = content;
    }

    @GetMapping("/kbs")
    public ApiResponse<List<JsonNode>> kbs() {
        return ApiResponse.ok(content.kbs());
    }

    @GetMapping("/docs")
    public ApiResponse<List<JsonNode>> docs() {
        return ApiResponse.ok(content.docs());
    }

    @GetMapping("/docs/{id}")
    public ApiResponse<JsonNode> doc(@PathVariable String id) {
        return ApiResponse.ok(content.doc(id));
    }

    @GetMapping("/jobs")
    public ApiResponse<List<JsonNode>> jobs() {
        return ApiResponse.ok(content.jobs());
    }

    @GetMapping("/files")
    public ApiResponse<List<JsonNode>> files() {
        return ApiResponse.ok(content.files());
    }

    @GetMapping("/images")
    public ApiResponse<List<JsonNode>> images() {
        return ApiResponse.ok(content.images());
    }

    @GetMapping("/vault")
    public ApiResponse<List<JsonNode>> vault() {
        return ApiResponse.ok(content.vault());
    }

    @GetMapping("/calendar/events")
    public ApiResponse<List<JsonNode>> events() {
        return ApiResponse.ok(content.events());
    }

    @GetMapping("/schedule")
    public ApiResponse<List<JsonNode>> schedule() {
        return ApiResponse.ok(content.schedule());
    }

    @GetMapping("/suggestions")
    public ApiResponse<List<JsonNode>> suggestions() {
        return ApiResponse.ok(content.suggestions());
    }

    @GetMapping("/goals")
    public ApiResponse<List<JsonNode>> goals() {
        return ApiResponse.ok(content.goals());
    }
}
