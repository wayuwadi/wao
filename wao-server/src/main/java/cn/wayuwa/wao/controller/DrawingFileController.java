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
                             List<cn.wayuwa.wao.mapper.DrawingGroupMapper.Assignment> assignments,
                             List<String> order, long revision) {}
    public record OrderRequest(List<String> order, long revision) {}
    private final com.fasterxml.jackson.databind.ObjectMapper directoryJson = new com.fasterxml.jackson.databind.ObjectMapper();

    @GetMapping("/groups")
    public ApiResponse<GroupIndex> groups(HttpServletRequest http) {
        String member = member(http);
        var saved = groups.directoryOrder(member);
        try {
            List<String> order = saved == null ? List.of() : directoryJson.readValue(saved.orderJson(), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            return ApiResponse.ok(new GroupIndex(groups.groups(member), groups.assignments(member), order, saved == null ? 0 : saved.revision()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("草图排序数据无法读取", e); }
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

    @PutMapping("/order")
    @org.springframework.transaction.annotation.Transactional
    public ApiResponse<Long> saveOrder(@RequestBody OrderRequest request, HttpServletRequest http) {
        String member = member(http);
        if (request.order() == null || request.order().size() > 10000 || request.revision() < 0 || new java.util.HashSet<>(request.order()).size() != request.order().size())
            throw new cn.wayuwa.wao.common.BadRequestException("草图排序数据无效");
        var allowed = new java.util.HashSet<String>();
        groups.groups(member).forEach(group -> allowed.add("g:" + group.id()));
        drawings.list(member).forEach(file -> allowed.add("f:" + file.id()));
        if (!allowed.containsAll(request.order())) throw new cn.wayuwa.wao.common.BadRequestException("草图排序包含不可访问的项目，请刷新后重试");
        try {
            groups.ensureDirectoryOrder(member);
            if (groups.saveDirectoryOrder(member, directoryJson.writeValueAsString(request.order()), request.revision()) != 1)
                throw new cn.wayuwa.wao.common.ConflictException("排序已在其他页面修改，请刷新后重试");
            return ApiResponse.ok(request.revision() + 1);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("草图排序无法保存", e); }
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
