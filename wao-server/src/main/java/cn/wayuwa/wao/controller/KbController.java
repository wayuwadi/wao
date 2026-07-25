package cn.wayuwa.wao.controller;

import java.util.List;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.CreateFolderRequest;
import cn.wayuwa.wao.dto.CreateKbRequest;
import cn.wayuwa.wao.dto.FolderDto;
import cn.wayuwa.wao.dto.KbDto;
import cn.wayuwa.wao.dto.PatchFolderRequest;
import cn.wayuwa.wao.dto.PatchKbRequest;
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

/** 知识库与文件夹接口。 */
@RestController
@RequestMapping("/api/kbs")
public class KbController {

    private final KnowledgeService knowledge;

    public KbController(KnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    @GetMapping
    public ApiResponse<List<KbDto>> list() {
        return ApiResponse.ok(knowledge.listKbs());
    }

    @GetMapping("/{id}")
    public ApiResponse<KbDto> get(@PathVariable String id) {
        return ApiResponse.ok(knowledge.getKb(id));
    }

    @PostMapping
    public ApiResponse<KbDto> create(@Valid @RequestBody CreateKbRequest request) {
        return ApiResponse.ok(knowledge.createKb(request));
    }

    @PatchMapping("/{id}")
    public ApiResponse<KbDto> patch(@PathVariable String id, @RequestBody PatchKbRequest request) {
        return ApiResponse.ok(knowledge.updateKb(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        knowledge.deleteKb(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/folders")
    public ApiResponse<FolderDto> createFolder(@PathVariable String id, @RequestBody CreateFolderRequest request) {
        return ApiResponse.ok(knowledge.createFolder(id, request));
    }

    @PatchMapping("/{id}/folders/{folderId}")
    public ApiResponse<FolderDto> patchFolder(@PathVariable String id, @PathVariable String folderId,
            @RequestBody PatchFolderRequest request) {
        return ApiResponse.ok(knowledge.renameFolder(id, folderId, request));
    }

    @DeleteMapping("/{id}/folders/{folderId}")
    public ApiResponse<Void> deleteFolder(@PathVariable String id, @PathVariable String folderId) {
        knowledge.deleteFolder(id, folderId);
        return ApiResponse.ok(null);
    }
}
