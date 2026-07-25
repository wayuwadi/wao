package cn.wayuwa.wao.controller;

import java.util.List;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.CreateDocRequest;
import cn.wayuwa.wao.dto.DocDto;
import cn.wayuwa.wao.dto.PatchDocRequest;
import cn.wayuwa.wao.service.KnowledgeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 文档接口。新建文档必须指定所属知识库与文件夹，创建即完成与知识库的关联。 */
@RestController
@RequestMapping("/api/docs")
public class DocController {

    private final KnowledgeService knowledge;

    public DocController(KnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    @GetMapping
    public ApiResponse<List<DocDto>> list() {
        return ApiResponse.ok(knowledge.listDocs());
    }

    @GetMapping("/{id}")
    public ApiResponse<DocDto> get(@PathVariable String id) {
        return ApiResponse.ok(knowledge.getDoc(id));
    }

    @PostMapping
    public ApiResponse<DocDto> create(@Valid @RequestBody CreateDocRequest request) {
        return ApiResponse.ok(knowledge.createDoc(request));
    }

    @PatchMapping("/{id}")
    public ApiResponse<DocDto> patch(@PathVariable String id, @RequestBody PatchDocRequest request) {
        return ApiResponse.ok(knowledge.updateDoc(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        knowledge.deleteDoc(id);
        return ApiResponse.ok(null);
    }
}
