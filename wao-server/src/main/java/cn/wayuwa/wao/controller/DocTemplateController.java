package cn.wayuwa.wao.controller;

import java.util.List;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.CreateDocTemplateRequest;
import cn.wayuwa.wao.dto.DocTemplateDto;
import cn.wayuwa.wao.dto.PatchDocTemplateRequest;
import cn.wayuwa.wao.service.KnowledgeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 文档模板管理接口；所有操作只作用于当前登录成员自己的模板。 */
@RestController
@RequestMapping("/api/doc-templates")
public class DocTemplateController {
    private final KnowledgeService knowledge;
    private final CurrentMember currentMember;

    public DocTemplateController(KnowledgeService knowledge, CurrentMember currentMember) {
        this.knowledge = knowledge;
        this.currentMember = currentMember;
    }

    @GetMapping
    public ApiResponse<List<DocTemplateDto>> list(HttpServletRequest http) {
        return ApiResponse.ok(knowledge.listTemplates(currentMember.id(http)));
    }

    @PostMapping
    public ApiResponse<DocTemplateDto> create(@Valid @RequestBody CreateDocTemplateRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(knowledge.createTemplate(request, currentMember.id(http)));
    }

    @PatchMapping("/{id}")
    public ApiResponse<DocTemplateDto> patch(@PathVariable String id,
            @RequestBody PatchDocTemplateRequest request, HttpServletRequest http) {
        return ApiResponse.ok(knowledge.updateTemplate(id, request, currentMember.id(http)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, HttpServletRequest http) {
        knowledge.deleteTemplate(id, currentMember.id(http));
        return ApiResponse.ok(null);
    }
}
