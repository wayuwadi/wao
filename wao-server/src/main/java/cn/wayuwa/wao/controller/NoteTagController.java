package cn.wayuwa.wao.controller;

import java.util.List;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.service.NoteTagService;
import org.springframework.web.bind.annotation.*;

/** 小记标签使用与文档相同的登录保护。 */
@RestController
@RequestMapping("/api/docs/note-tags")
public class NoteTagController {
    private final NoteTagService tags;
    public NoteTagController(NoteTagService tags) { this.tags = tags; }
    public record CreateTagRequest(String name) {}

    @GetMapping
    public ApiResponse<List<String>> list() { return ApiResponse.ok(tags.list()); }

    @PostMapping
    public ApiResponse<String> create(@RequestBody CreateTagRequest request) {
        return ApiResponse.ok(tags.create(request.name()));
    }
}
