package cn.wayuwa.wao.controller;

import java.util.List;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.FavoriteDto;
import cn.wayuwa.wao.service.FavoriteService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 成员收藏接口，按登录成员维度隔离。 */
@RestController
@RequestMapping("/api/favorites")
public class FavoriteController {

    /** 关掉登录保护的本地 / dev 环境只有一个身份，按管理员处理。 */
    private static final String FALLBACK_OWNER = "wg";

    private final FavoriteService favoriteService;
    private final CurrentMember currentMember;

    public FavoriteController(FavoriteService favoriteService, CurrentMember currentMember) {
        this.favoriteService = favoriteService;
        this.currentMember = currentMember;
    }

    private String owner(HttpServletRequest http) {
        return currentMember.idOr(http, FALLBACK_OWNER);
    }

    @GetMapping
    public ApiResponse<List<FavoriteDto>> list(HttpServletRequest http) {
        return ApiResponse.ok(favoriteService.list(owner(http)));
    }

    @PutMapping("/{type}/{targetId}")
    public ApiResponse<Void> add(@PathVariable String type, @PathVariable String targetId,
            HttpServletRequest http) {
        favoriteService.add(owner(http), type, targetId);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/{type}/{targetId}")
    public ApiResponse<Void> remove(@PathVariable String type, @PathVariable String targetId,
            HttpServletRequest http) {
        favoriteService.remove(owner(http), type, targetId);
        return ApiResponse.ok(null);
    }
}
