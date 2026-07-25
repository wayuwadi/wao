package cn.wayuwa.wao.controller;

import java.util.List;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.CreateKbGroupRequest;
import cn.wayuwa.wao.dto.KbGroupDto;
import cn.wayuwa.wao.dto.PatchKbGroupRequest;
import cn.wayuwa.wao.service.KnowledgeService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 用户自建知识库分组接口。 */
@RestController
@RequestMapping("/api/kb-groups")
public class KbGroupController {

    private final KnowledgeService knowledge;

    public KbGroupController(KnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    @GetMapping
    public ApiResponse<List<KbGroupDto>> list() {
        return ApiResponse.ok(knowledge.listGroups());
    }

    @PostMapping
    public ApiResponse<KbGroupDto> create(@RequestBody CreateKbGroupRequest request) {
        return ApiResponse.ok(knowledge.createGroup(request));
    }

    @PatchMapping("/{id}")
    public ApiResponse<KbGroupDto> patch(@PathVariable String id, @RequestBody PatchKbGroupRequest request) {
        return ApiResponse.ok(knowledge.renameGroup(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        knowledge.deleteGroup(id);
        return ApiResponse.ok(null);
    }
}
