package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.dto.FoodWorkspaceDto;
import cn.wayuwa.wao.service.FoodService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 当前登录成员的饮食管理工作区接口。 */
@RestController
@RequestMapping("/api/food")
public class FoodController {
    private final FoodService service;
    private final CurrentMember currentMember;

    public FoodController(FoodService service, CurrentMember currentMember) {
        this.service = service;
        this.currentMember = currentMember;
    }

    /** 读取当前成员饮食工作区。 */
    @GetMapping
    public ApiResponse<FoodWorkspaceDto> workspace(HttpServletRequest request) {
        return ApiResponse.ok(service.workspace(member(request)));
    }

    /** 保存当前成员饮食工作区。 */
    @PutMapping
    public ApiResponse<FoodWorkspaceDto> save(@RequestBody FoodWorkspaceDto body, HttpServletRequest request) {
        return ApiResponse.ok(service.save(member(request), body));
    }

    private String member(HttpServletRequest request) {
        String memberId = currentMember.id(request);
        if (!StringUtils.hasText(memberId)) throw new UnauthorizedException("登录成员不存在，请重新登录");
        return memberId;
    }
}
