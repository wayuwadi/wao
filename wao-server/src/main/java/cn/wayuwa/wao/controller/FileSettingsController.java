package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.FileSettingsDto;
import cn.wayuwa.wao.dto.SaveFileSettingsRequest;
import cn.wayuwa.wao.service.FileSettingsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 文件与图床设置接口。 */
@RestController
@RequestMapping("/api/settings/files")
public class FileSettingsController {

    private final FileSettingsService settings;

    public FileSettingsController(FileSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    public ApiResponse<FileSettingsDto> get() {
        return ApiResponse.ok(settings.get());
    }

    @PutMapping
    public ApiResponse<FileSettingsDto> save(@Valid @RequestBody SaveFileSettingsRequest request) {
        return ApiResponse.ok(settings.save(request));
    }
}
