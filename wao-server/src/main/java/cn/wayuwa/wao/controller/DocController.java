package cn.wayuwa.wao.controller;

import java.time.LocalDateTime;
import java.util.List;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.CreateDocRequest;
import cn.wayuwa.wao.dto.CreateNoteRequest;
import cn.wayuwa.wao.dto.DocDto;
import cn.wayuwa.wao.dto.DocPresenceDto;
import cn.wayuwa.wao.dto.DocPresenceRequest;
import cn.wayuwa.wao.dto.PatchDocRequest;
import cn.wayuwa.wao.service.DocPresenceService;
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

/**
 * 文档接口。新建文档必须指定所属知识库与文件夹，创建即完成与知识库的关联；
 * 小记（{@code POST /api/docs/notes}）例外，它一律落到小记知识库。
 */
@RestController
@RequestMapping("/api/docs")
public class DocController {

    private final KnowledgeService knowledge;
    private final DocPresenceService presence;
    private final CurrentMember currentMember;

    public DocController(KnowledgeService knowledge, DocPresenceService presence, CurrentMember currentMember) {
        this.knowledge = knowledge;
        this.presence = presence;
        this.currentMember = currentMember;
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
    public ApiResponse<DocDto> create(@Valid @RequestBody CreateDocRequest request, HttpServletRequest http) {
        return ApiResponse.ok(knowledge.createDoc(request, currentMember.id(http)));
    }

    /** 新建一条小记：不需要选知识库，直接落到小记知识库。 */
    @PostMapping("/notes")
    public ApiResponse<DocDto> createNote(@RequestBody CreateNoteRequest request, HttpServletRequest http) {
        return ApiResponse.ok(knowledge.createNote(request, currentMember.id(http)));
    }

    @PatchMapping("/{id}")
    public ApiResponse<DocDto> patch(@PathVariable String id, @RequestBody PatchDocRequest request) {
        return ApiResponse.ok(knowledge.updateDoc(id, request));
    }

    /** 删除文档 —— 移入回收站，7 天内可恢复。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, HttpServletRequest http) {
        knowledge.deleteDoc(id, currentMember.id(http));
        return ApiResponse.ok(null);
    }

    /** 协同在场心跳：上报光标位置，取回同刻的协作者与（版本落后时的）最新正文。 */
    @PostMapping("/{id}/presence")
    public ApiResponse<DocPresenceDto> presence(@PathVariable String id, @RequestBody DocPresenceRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(presence.heartbeat(id, currentMember.id(http), request, LocalDateTime.now()));
    }

    /** 离开文档，让协作者那边的头像立刻消失。 */
    @DeleteMapping("/{id}/presence")
    public ApiResponse<Void> leave(@PathVariable String id, HttpServletRequest http) {
        presence.leave(id, currentMember.id(http));
        return ApiResponse.ok(null);
    }
}
