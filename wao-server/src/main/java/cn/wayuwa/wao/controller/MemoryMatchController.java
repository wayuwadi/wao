package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.GameRevisionRequest;
import cn.wayuwa.wao.dto.MemoryMatchDto;
import cn.wayuwa.wao.dto.MemoryMatchFlipRequest;
import cn.wayuwa.wao.service.MemoryMatchService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 记忆翻牌的服务端权威牌局接口。 */
@RestController
@RequestMapping("/api/games/memory-match")
public class MemoryMatchController {

    private static final String FALLBACK_OWNER = "wg";

    private final MemoryMatchService game;
    private final CurrentMember currentMember;

    public MemoryMatchController(MemoryMatchService game, CurrentMember currentMember) {
        this.game = game;
        this.currentMember = currentMember;
    }

    @GetMapping
    public ApiResponse<MemoryMatchDto> get(HttpServletRequest http) {
        return ApiResponse.ok(game.get(owner(http)));
    }

    @PostMapping("/flip")
    public ApiResponse<MemoryMatchDto> flip(
            @Valid @RequestBody MemoryMatchFlipRequest request, HttpServletRequest http) {
        return ApiResponse.ok(game.flip(owner(http), request.index(), request.revision()));
    }

    @PostMapping("/restart")
    public ApiResponse<MemoryMatchDto> restart(
            @Valid @RequestBody GameRevisionRequest request, HttpServletRequest http) {
        return ApiResponse.ok(game.restart(owner(http), request.revision()));
    }

    private String owner(HttpServletRequest http) {
        return currentMember.idOr(http, FALLBACK_OWNER);
    }
}
