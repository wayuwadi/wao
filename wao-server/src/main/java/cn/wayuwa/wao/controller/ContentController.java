package cn.wayuwa.wao.controller;

import java.util.List;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.service.ContentService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内容 / 参考域只读接口（文件、日历、日程、建议、目标）。
 * 图床见 {@link ImageController}（含上传，不再是只读）；知识库 / 文档见 {@link KbController} /
 * {@link DocController}；自动化见 {@link AutomationController}；密钥库见 {@link VaultController}。
 */
@RestController
@RequestMapping("/api")
public class ContentController {

    private final ContentService content;

    public ContentController(ContentService content) {
        this.content = content;
    }

    @GetMapping("/files")
    public ApiResponse<List<JsonNode>> files() {
        return ApiResponse.ok(content.files());
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
