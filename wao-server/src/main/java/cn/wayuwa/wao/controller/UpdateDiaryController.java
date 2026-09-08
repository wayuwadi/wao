package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.UpdateDiaryPageDto;
import cn.wayuwa.wao.service.UpdateDiaryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 登录成员查看 WAO 已成功交付的产品更新日记。 */
@RestController
@RequestMapping("/api/update-diary")
public class UpdateDiaryController {
    private final UpdateDiaryService service;

    public UpdateDiaryController(UpdateDiaryService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<UpdateDiaryPageDto> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int size) {
        return ApiResponse.ok(service.page(page, size));
    }
}
