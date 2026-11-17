package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.service.DrawingPublicShareService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
public class DrawingPublicShareController {
    private final CurrentMember member;
    private final DrawingPublicShareService service;

    public record CreateRequest(String validity) {}

    public DrawingPublicShareController(CurrentMember member, DrawingPublicShareService service) {
        this.member = member;
        this.service = service;
    }

    @GetMapping("/api/drawings/{id}/public-shares")
    public ApiResponse<List<DrawingPublicShareService.Entry>> list(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.ok(service.list(member.id(request), id));
    }

    @PostMapping("/api/drawings/{id}/public-shares")
    public ApiResponse<DrawingPublicShareService.Created> create(@PathVariable String id,
            @RequestBody CreateRequest body, HttpServletRequest request) {
        return ApiResponse.ok(service.create(member.id(request), id, body.validity()));
    }

    @DeleteMapping("/api/drawings/{id}/public-shares/{shareId}")
    public ApiResponse<Void> revoke(@PathVariable String id, @PathVariable String shareId, HttpServletRequest request) {
        service.revoke(member.id(request), id, shareId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/api/share/drawings/current")
    public ApiResponse<DrawingPublicShareService.PublicScene> read(
            @RequestHeader(name = "X-Drawing-Share-Token", required = false) String token,
            @RequestParam(defaultValue = "0") int knownRevision) {
        return ApiResponse.ok(service.read(token, knownRevision));
    }
}
