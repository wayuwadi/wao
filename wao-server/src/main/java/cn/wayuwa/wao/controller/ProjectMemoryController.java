package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.ProjectMemoryDto;
import cn.wayuwa.wao.dto.SaveProjectMemoryRequest;
import cn.wayuwa.wao.service.ProjectMemoryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 项目授权成员共享的工程记忆管理接口。 */
@RestController
@RequestMapping("/api/projects/{projectId}/memories")
public class ProjectMemoryController {
    private final ProjectMemoryService memories;
    private final CurrentMember currentMember;

    public ProjectMemoryController(ProjectMemoryService memories, CurrentMember currentMember) {
        this.memories = memories;
        this.currentMember = currentMember;
    }

    @GetMapping
    public ApiResponse<List<ProjectMemoryDto>> list(@PathVariable String projectId,
            @RequestParam(required = false) String query, @RequestParam(required = false) String category,
            HttpServletRequest http) {
        return ApiResponse.ok(memories.list(projectId, currentMember.idOr(http, "wg"), query, category));
    }

    @PostMapping
    public ApiResponse<ProjectMemoryDto> create(@PathVariable String projectId,
            @Valid @RequestBody SaveProjectMemoryRequest request, HttpServletRequest http) {
        return ApiResponse.ok(memories.create(projectId, currentMember.idOr(http, "wg"), request));
    }

    @PatchMapping("/{memoryId}")
    public ApiResponse<ProjectMemoryDto> update(@PathVariable String projectId, @PathVariable String memoryId,
            @Valid @RequestBody SaveProjectMemoryRequest request, HttpServletRequest http) {
        return ApiResponse.ok(memories.update(projectId, memoryId, currentMember.idOr(http, "wg"), request));
    }

    @DeleteMapping("/{memoryId}")
    public ApiResponse<Void> delete(@PathVariable String projectId, @PathVariable String memoryId,
            HttpServletRequest http) {
        memories.delete(projectId, memoryId, currentMember.idOr(http, "wg"));
        return ApiResponse.ok(null);
    }
}
