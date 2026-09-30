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
import cn.wayuwa.wao.service.NoteAiWorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文档接口。新建文档必须指定所属知识库与文件夹，创建即完成与知识库的关联；
 * 小记（{@code POST /api/docs/notes} 或幂等的 {@code PUT /api/docs/notes/{id}}）例外，
 * 它一律落到小记知识库。
 */
@RestController
@RequestMapping("/api/docs")
public class DocController {
    private static final Logger log = LoggerFactory.getLogger(DocController.class);

    private final KnowledgeService knowledge;
    private final NoteAiWorkflowService noteAi;
    private final DocPresenceService presence;
    private final CurrentMember currentMember;

    public DocController(KnowledgeService knowledge, DocPresenceService presence, CurrentMember currentMember,
            NoteAiWorkflowService noteAi) {
        this.knowledge = knowledge;
        this.presence = presence;
        this.currentMember = currentMember;
        this.noteAi = noteAi;
    }

    @GetMapping
    public ApiResponse<List<DocDto>> list() {
        return ApiResponse.ok(knowledge.listDocs());
    }

    /** 目录不预取普通文档正文；小记保留全文供列表展示和内容搜索。 */
    @GetMapping("/catalog")
    public ApiResponse<List<DocDto>> catalog() {
        return ApiResponse.ok(knowledge.listDocCatalog());
    }

    /** 列出一个知识库中长期保留、但已退出日常文档树的归档文档。 */
    @GetMapping("/archived")
    public ApiResponse<List<DocDto>> archived(@RequestParam String kbId) {
        return ApiResponse.ok(knowledge.listArchivedDocs(kbId));
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
        String memberId = currentMember.id(http);
        DocDto saved = knowledge.createNote(request, memberId);
        scheduleNoteAi(memberId, saved, request);
        return ApiResponse.ok(saved);
    }

    /**
     * 幂等新建一条小记：客户端为草稿保留稳定 id，弱网重试只会更新同一篇，不会生成重复小记。
     */
    @PutMapping("/notes/{id}")
    public ApiResponse<DocDto> putNote(@PathVariable String id, @RequestBody CreateNoteRequest request,
            HttpServletRequest http) {
        String memberId = currentMember.id(http);
        DocDto saved = knowledge.createNote(id, request, memberId);
        scheduleNoteAi(memberId, saved, request);
        return ApiResponse.ok(saved);
    }

    private void scheduleNoteAi(String memberId, DocDto saved, CreateNoteRequest request) {
        try { noteAi.beginIfRequested(memberId, saved, request); }
        catch (RuntimeException ex) {
            // 正文已经保存成功；AI 调度异常不能让幂等重试误报正文保存失败。
            log.warn("小记已保存但自动整理未能入队：{}", saved.id(), ex);
        }
    }

    @PatchMapping("/{id}")
    public ApiResponse<DocDto> patch(@PathVariable String id, @RequestBody PatchDocRequest request,
            HttpServletRequest http) {
        DocDto saved = knowledge.updateDoc(id, request);
        try { noteAi.refreshAfterSave(currentMember.id(http), saved.id()); }
        catch (RuntimeException ex) { log.warn("小记已保存但最新正文整理未能入队：{}", saved.id(), ex); }
        return ApiResponse.ok(saved);
    }

    /** 把一次性文档移出日常文档树并长期保留。 */
    @PostMapping("/{id}/archive")
    public ApiResponse<DocDto> archive(@PathVariable String id, HttpServletRequest http) {
        return ApiResponse.ok(knowledge.archiveDoc(id, currentMember.id(http)));
    }

    /** 取消归档，把文档放回原文件夹。 */
    @DeleteMapping("/{id}/archive")
    public ApiResponse<DocDto> unarchive(@PathVariable String id) {
        return ApiResponse.ok(knowledge.unarchiveDoc(id));
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
