package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.service.DrawingMemberLibraryService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/drawings/library")
public class DrawingMemberLibraryController {
    private final CurrentMember currentMember;
    private final DrawingMemberLibraryService library;

    public DrawingMemberLibraryController(CurrentMember currentMember, DrawingMemberLibraryService library) {
        this.currentMember = currentMember;
        this.library = library;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<JsonNode>>> get(HttpServletRequest request) {
        return response(library.read(member(request)));
    }

    @PatchMapping
    public ResponseEntity<ApiResponse<List<JsonNode>>> change(
            @RequestBody DrawingMemberLibraryService.Change change, HttpServletRequest request) {
        return response(library.change(member(request), change));
    }

    private ResponseEntity<ApiResponse<List<JsonNode>>> response(List<JsonNode> items) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(items));
    }

    private String member(HttpServletRequest request) {
        String id = currentMember.id(request);
        if (id == null || id.isBlank()) throw new UnauthorizedException("请登录后使用账号素材库");
        return id;
    }
}
