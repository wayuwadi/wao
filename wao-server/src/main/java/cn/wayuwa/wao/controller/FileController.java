package cn.wayuwa.wao.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.domain.FileBlobEntity;
import cn.wayuwa.wao.dto.FileItemDto;
import cn.wayuwa.wao.service.FileService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 真实文件模块接口。下载地址可直接打开，上传和删除需要登录会话。 */
@RestController
@RequestMapping("/api/files")
public class FileController {
    private final FileService files;
    private final CurrentMember currentMember;
    public FileController(FileService files, CurrentMember currentMember) { this.files = files; this.currentMember = currentMember; }

    @GetMapping public ApiResponse<List<FileItemDto>> list() { return ApiResponse.ok(files.list()); }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<FileItemDto> upload(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
        return ApiResponse.ok(files.upload(file, currentMember.id(request)));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable String id) {
        FileBlobEntity entity = files.raw(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(entity.getContentType()))
                .header("Content-Disposition", ContentDisposition.attachment()
                        .filename(entity.getName(), StandardCharsets.UTF_8).build().toString())
                .body(entity.getContent());
    }

    @DeleteMapping("/{id}") public ApiResponse<Void> delete(@PathVariable String id) { files.delete(id); return ApiResponse.ok(null); }
}
