package cn.wayuwa.wao.controller;

import java.util.List;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.dto.TrashEntryDto;
import cn.wayuwa.wao.service.DrawingFileService;
import cn.wayuwa.wao.service.KnowledgeService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 知识库和个人草图的回收站接口。 */
@RestController
@RequestMapping("/api/trash")
public class TrashController {

    private final KnowledgeService knowledge;
    private final DrawingFileService drawings;
    private final CurrentMember currentMember;

    public TrashController(KnowledgeService knowledge, DrawingFileService drawings, CurrentMember currentMember) {
        this.knowledge = knowledge;
        this.drawings = drawings;
        this.currentMember = currentMember;
    }

    @GetMapping
    public ApiResponse<List<TrashEntryDto>> list(@RequestParam(required = false) String type, HttpServletRequest http) {
        if ("drawing".equals(type)) return ApiResponse.ok(drawings.listTrash(member(http)));
        return ApiResponse.ok(knowledge.listTrash());
    }

    /** 恢复一个条目；父级也在回收站里时一并恢复。 */
    @PostMapping("/{type}/{id}/restore")
    public ApiResponse<Void> restore(@PathVariable String type, @PathVariable String id, HttpServletRequest http) {
        if ("drawing".equals(type)) drawings.restoreTrash(id, member(http));
        else knowledge.restore(type, id);
        return ApiResponse.ok(null);
    }

    /** 立刻彻底删除，不等 7 天。 */
    @DeleteMapping("/{type}/{id}")
    public ApiResponse<Void> purge(@PathVariable String type, @PathVariable String id) {
        knowledge.purge(type, id);
        return ApiResponse.ok(null);
    }

    private String member(HttpServletRequest http) {
        String memberId = currentMember.id(http);
        if (memberId == null) throw new UnauthorizedException("请登录后查看草图回收站");
        return memberId;
    }
}
