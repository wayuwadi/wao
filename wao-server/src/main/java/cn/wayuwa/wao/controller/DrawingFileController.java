package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.CreateDrawingFileRequest;
import cn.wayuwa.wao.dto.DrawingFileDto;
import cn.wayuwa.wao.dto.DrawingFileSummaryDto;
import cn.wayuwa.wao.dto.DrawingFileVersionDto;
import cn.wayuwa.wao.dto.RenameDrawingFileRequest;
import cn.wayuwa.wao.dto.RestoreDrawingFileVersionRequest;
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
    @org.springframework.beans.factory.annotation.Autowired private cn.wayuwa.wao.mapper.DrawingGroupMapper groups;

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

    @GetMapping("/{id}/versions")
    public ApiResponse<List<DrawingFileVersionDto>> versions(
            @PathVariable String id, HttpServletRequest http) {
        return ApiResponse.ok(drawings.versions(id, member(http)));
    }

    @PostMapping("/{id}/versions/{revision}/restore")
    public ApiResponse<DrawingFileDto> restore(@PathVariable String id, @PathVariable int revision,
            @Valid @RequestBody RestoreDrawingFileVersionRequest request, HttpServletRequest http) {
        return ApiResponse.ok(drawings.restore(id, revision, request.revision(), member(http)));
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

    public record GroupRequest(String name) {}
    public record MoveRequest(String groupId) {}
    public record GroupIndex(List<cn.wayuwa.wao.mapper.DrawingGroupMapper.Group> groups,
                             List<cn.wayuwa.wao.mapper.DrawingGroupMapper.Assignment> assignments) {}

    @GetMapping("/groups")
    public ApiResponse<GroupIndex> groups(HttpServletRequest http) {
        String member = member(http);
        return ApiResponse.ok(new GroupIndex(groups.groups(member), groups.assignments(member)));
    }

    @PostMapping("/groups")
    public ApiResponse<cn.wayuwa.wao.mapper.DrawingGroupMapper.Group> createGroup(@RequestBody GroupRequest request, HttpServletRequest http) {
        String name = groupName(request.name());
        String id = "dg" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 28);
        groups.create(id, member(http), name);
        return ApiResponse.ok(new cn.wayuwa.wao.mapper.DrawingGroupMapper.Group(id, name));
    }

    @PatchMapping("/groups/{id}")
    public ApiResponse<Void> renameGroup(@PathVariable String id, @RequestBody GroupRequest request, HttpServletRequest http) {
        if (groups.owned(id, member(http)) == 0) throw new cn.wayuwa.wao.common.NotFoundException("分组不存在");
        groups.rename(id, member(http), groupName(request.name()));
        return ApiResponse.ok(null);
    }

    @PutMapping("/{id}/group")
    public ApiResponse<Void> move(@PathVariable String id, @RequestBody MoveRequest request, HttpServletRequest http) {
        String member = member(http);
        drawings.get(id, member);
        if (request.groupId() == null || request.groupId().isBlank()) groups.unassign(member, id);
        else {
            if (groups.owned(request.groupId(), member) == 0) throw new cn.wayuwa.wao.common.NotFoundException("分组不存在");
            groups.assign(member, id, request.groupId());
        }
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/copy")
    public ApiResponse<DrawingFileDto> copy(@PathVariable String id, HttpServletRequest http) {
        return ApiResponse.ok(drawings.copy(id, member(http)));
    }

    private String groupName(String value) {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty() || name.length() > 128) throw new cn.wayuwa.wao.common.BadRequestException("分组名称须为 1～128 个字符");
        return name;
    }

    private String member(HttpServletRequest http) {
        return currentMember.idOr(http, FALLBACK_MEMBER);
    }
}
