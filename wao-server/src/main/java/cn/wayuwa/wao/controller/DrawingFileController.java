package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.CreateDrawingFileRequest;
import cn.wayuwa.wao.dto.DrawingFileDto;
import cn.wayuwa.wao.dto.DrawingFileSummaryDto;
import cn.wayuwa.wao.dto.RenameDrawingFileRequest;
import cn.wayuwa.wao.dto.SaveDrawingFileRequest;
import cn.wayuwa.wao.service.DrawingFileService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 当前登录成员的草图文件接口。 */
@RestController
@RequestMapping("/api/drawings")
public class DrawingFileController {

    private static final String FALLBACK_MEMBER = "wg";
    private final DrawingFileService drawings;
    private final CurrentMember currentMember;

    public DrawingFileController(DrawingFileService drawings, CurrentMember currentMember) {
        this.drawings = drawings;
        this.currentMember = currentMember;
    }

    @GetMapping
    public ApiResponse<List<DrawingFileSummaryDto>> list(HttpServletRequest http) {
        return ApiResponse.ok(drawings.list(member(http)));
    }

    @PostMapping
    public ApiResponse<DrawingFileDto> create(
            @Valid @RequestBody(required = false) CreateDrawingFileRequest request, HttpServletRequest http) {
        return ApiResponse.ok(drawings.create(request, member(http)));
    }

    @GetMapping("/{id}")
    public ApiResponse<DrawingFileDto> get(@PathVariable String id, HttpServletRequest http) {
        return ApiResponse.ok(drawings.get(id, member(http)));
    }

    @PutMapping("/{id}")
    public ApiResponse<DrawingFileDto> save(@PathVariable String id,
            @Valid @RequestBody SaveDrawingFileRequest request, HttpServletRequest http) {
        return ApiResponse.ok(drawings.save(id, request, member(http)));
    }

    @PatchMapping("/{id}")
    public ApiResponse<DrawingFileSummaryDto> rename(@PathVariable String id,
            @Valid @RequestBody RenameDrawingFileRequest request, HttpServletRequest http) {
        return ApiResponse.ok(drawings.rename(id, request, member(http)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, HttpServletRequest http) {
        drawings.delete(id, member(http));
        return ApiResponse.ok(null);
    }

    private String member(HttpServletRequest http) {
        return currentMember.idOr(http, FALLBACK_MEMBER);
    }
}
