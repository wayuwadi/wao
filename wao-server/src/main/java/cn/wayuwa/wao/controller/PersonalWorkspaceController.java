package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.CreateWorkspaceRequest;
import cn.wayuwa.wao.dto.SaveWorkspaceRequest;
import cn.wayuwa.wao.dto.WorkspaceDetailDto;
import cn.wayuwa.wao.dto.WorkspaceSummaryDto;
import cn.wayuwa.wao.service.PersonalWorkspaceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 当前登录成员的个人工作空间接口。 */
@RestController
@RequestMapping("/api/workspaces")
public class PersonalWorkspaceController {

    private static final String FALLBACK_MEMBER = "wg";
    private final PersonalWorkspaceService workspaces;
    private final CurrentMember currentMember;

    public PersonalWorkspaceController(PersonalWorkspaceService workspaces, CurrentMember currentMember) {
        this.workspaces = workspaces;
        this.currentMember = currentMember;
    }

    @GetMapping
    public ApiResponse<List<WorkspaceSummaryDto>> list(HttpServletRequest http) {
        return ApiResponse.ok(workspaces.list(member(http)));
    }

    @GetMapping("/{id}")
    public ApiResponse<WorkspaceDetailDto> get(@PathVariable String id, HttpServletRequest http) {
        return ApiResponse.ok(workspaces.get(id, member(http)));
    }

    @PostMapping
    public ApiResponse<WorkspaceDetailDto> create(@Valid @RequestBody CreateWorkspaceRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(workspaces.create(request, member(http)));
    }

    @PutMapping("/{id}")
    public ApiResponse<WorkspaceDetailDto> save(@PathVariable String id,
            @Valid @RequestBody SaveWorkspaceRequest request, HttpServletRequest http) {
        return ApiResponse.ok(workspaces.save(id, request, member(http)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, HttpServletRequest http) {
        workspaces.delete(id, member(http));
        return ApiResponse.ok(null);
    }

    private String member(HttpServletRequest http) {
        return currentMember.idOr(http, FALLBACK_MEMBER);
    }
}
