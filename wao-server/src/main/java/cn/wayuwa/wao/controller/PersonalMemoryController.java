package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.domain.PersonalMemoryEntity;
import cn.wayuwa.wao.dto.CreatePersonalMemoryRequest;
import cn.wayuwa.wao.dto.PersonalMemoryDto;
import cn.wayuwa.wao.dto.UpdatePersonalMemoryRequest;
import cn.wayuwa.wao.service.PersonalMemoryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 当前登录成员的个人多模态记忆接口。 */
@RestController
@RequestMapping("/api/memory/entries")
public class PersonalMemoryController {
    private final PersonalMemoryService memories;
    private final CurrentMember currentMember;

    public PersonalMemoryController(PersonalMemoryService memories, CurrentMember currentMember) {
        this.memories = memories;
        this.currentMember = currentMember;
    }

    @GetMapping
    public ApiResponse<List<PersonalMemoryDto>> list(@RequestParam(required = false) String query,
            @RequestParam(required = false) String category, HttpServletRequest http) {
        return ApiResponse.ok(memories.list(member(http), query, category));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<PersonalMemoryDto> create(@Valid @RequestBody CreatePersonalMemoryRequest request,
            HttpServletRequest http) {
        return ApiResponse.ok(memories.createManualText(member(http),
                request.title(), request.content(), request.category()));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<PersonalMemoryDto> upload(@RequestParam(required = false) String title,
            @RequestParam(required = false) String content, @RequestParam String category,
            @RequestParam("file") MultipartFile file, HttpServletRequest http) {
        return ApiResponse.ok(memories.upload(member(http),
                title, content, category, file));
    }

    @PatchMapping("/{id}")
    public ApiResponse<PersonalMemoryDto> update(@PathVariable String id,
            @Valid @RequestBody UpdatePersonalMemoryRequest request, HttpServletRequest http) {
        return ApiResponse.ok(memories.update(member(http), id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, HttpServletRequest http) {
        memories.delete(member(http), id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/{id}/attachment")
    public ResponseEntity<byte[]> attachment(@PathVariable String id, HttpServletRequest http) {
        PersonalMemoryEntity memory = memories.attachment(member(http), id);
        MediaType type;
        try { type = MediaType.parseMediaType(memory.getContentType()); }
        catch (IllegalArgumentException ignored) { type = MediaType.APPLICATION_OCTET_STREAM; }
        return ResponseEntity.ok().contentType(type)
                .header("Cache-Control", "private, no-store")
                .header("Content-Disposition", ContentDisposition.attachment()
                        .filename(memory.getFileName(), StandardCharsets.UTF_8).build().toString())
                .body(memory.getFileContent());
    }

    private String member(HttpServletRequest http) {
        String memberId = currentMember.id(http);
        if (!StringUtils.hasText(memberId)) throw new UnauthorizedException("登录成员不存在，请重新登录");
        return memberId;
    }
}
