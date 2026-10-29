package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.NavigationLayoutDto;
import cn.wayuwa.wao.service.NavigationLayoutService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 个人布局只以当前登录成员为所有者，不支持请求方选择其他成员。 */
@RestController
@RequestMapping("/api/navigation/layout")
public class NavigationLayoutController {
    private final NavigationLayoutService layouts;
    private final CurrentMember member;

    public NavigationLayoutController(NavigationLayoutService layouts, CurrentMember member) {
        this.layouts = layouts;
        this.member = member;
    }

    @GetMapping
    public ApiResponse<NavigationLayoutDto.Snapshot> get(HttpServletRequest request) {
        return ApiResponse.ok(layouts.get(member.id(request)));
    }

    @PutMapping
    public ApiResponse<NavigationLayoutDto.Snapshot> put(@RequestBody NavigationLayoutDto.Save body,
            HttpServletRequest request) {
        throw new cn.wayuwa.wao.common.BadRequestException("菜单由开发人员统一维护，不再支持个人配置");
    }
}
