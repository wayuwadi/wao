package cn.wayuwa.wao.controller;

import java.util.List;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.TrashEntryDto;
import cn.wayuwa.wao.service.KnowledgeService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 知识库回收站接口：删除的知识库 / 文件夹 / 文档在这里保留 7 天，可恢复或立刻彻底删除。 */
@RestController
@RequestMapping("/api/trash")
public class TrashController {

    private final KnowledgeService knowledge;

    public TrashController(KnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    @GetMapping
    public ApiResponse<List<TrashEntryDto>> list() {
        return ApiResponse.ok(knowledge.listTrash());
    }

    /** 恢复一个条目；父级也在回收站里时一并恢复。 */
    @PostMapping("/{type}/{id}/restore")
    public ApiResponse<Void> restore(@PathVariable String type, @PathVariable String id) {
        knowledge.restore(type, id);
        return ApiResponse.ok(null);
    }

    /** 立刻彻底删除，不等 7 天。 */
    @DeleteMapping("/{type}/{id}")
    public ApiResponse<Void> purge(@PathVariable String type, @PathVariable String id) {
        knowledge.purge(type, id);
        return ApiResponse.ok(null);
    }
}
