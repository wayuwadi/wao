package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.RunTextPolishRequest;
import cn.wayuwa.wao.dto.SaveTextPolishPromptRequest;
import cn.wayuwa.wao.dto.TextPolishRecordDto;
import cn.wayuwa.wao.dto.TextPolishWorkspaceDto;
import cn.wayuwa.wao.service.TextPolishService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 当前成员私有的独立文本润色工作台接口。 */
@RestController
@RequestMapping("/api/text-polish")
public class TextPolishController {
    private final TextPolishService service;
    private final CurrentMember currentMember;

    public TextPolishController(TextPolishService service, CurrentMember currentMember) {
        this.service = service;
        this.currentMember = currentMember;
    }

    /** 读取成员提示词与最近历史。 */
    @GetMapping
    public ApiResponse<TextPolishWorkspaceDto> workspace(HttpServletRequest request) {
        return ApiResponse.ok(service.workspace(currentMember.idOr(request, "unknown")));
    }

    /** 保存成员级润色提示词。 */
    @PutMapping("/prompt")
    public ApiResponse<String> savePrompt(@Valid @RequestBody SaveTextPolishPromptRequest body,
            HttpServletRequest request) {
        return ApiResponse.ok(service.savePrompt(currentMember.idOr(request, "unknown"), body.prompt()));
    }

    /** 使用服务端已保存提示词执行真实 AI 润色并记录前后内容。 */
    @PostMapping("/run")
    public ApiResponse<TextPolishRecordDto> run(@Valid @RequestBody RunTextPolishRequest body,
            HttpServletRequest request) {
        return ApiResponse.ok(service.run(currentMember.idOr(request, "unknown"), body.originalText()));
    }
}
