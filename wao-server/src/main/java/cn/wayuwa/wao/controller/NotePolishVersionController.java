package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.NotePolishVersionDto;
import cn.wayuwa.wao.dto.SaveNotePolishVersionRequest;
import cn.wayuwa.wao.service.NotePolishVersionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 小记润色版读取和保存接口。 */
@RestController
@RequestMapping("/api/docs/notes/polish-versions")
public class NotePolishVersionController {
    private final NotePolishVersionService versions;
    private final CurrentMember member;

    public NotePolishVersionController(NotePolishVersionService versions, CurrentMember member) {
        this.versions = versions;
        this.member = member;
    }

    @GetMapping
    public ApiResponse<List<NotePolishVersionDto>> list(HttpServletRequest http) {
        return ApiResponse.ok(versions.list(member.id(http)));
    }

    @GetMapping("/reading")
    public ApiResponse<List<NotePolishVersionService.ReadingVersion>> reading(HttpServletRequest http){return ApiResponse.ok(versions.reading(member.id(http)));}

    @PutMapping("/{docId}")
    public ApiResponse<NotePolishVersionDto> save(@PathVariable String docId,
            @Valid @RequestBody SaveNotePolishVersionRequest request, HttpServletRequest http) {
        return ApiResponse.ok(versions.save(member.id(http), docId, request));
    }
}
