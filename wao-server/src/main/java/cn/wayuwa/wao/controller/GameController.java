package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.GameMoveRequest;
import cn.wayuwa.wao.dto.GameRevisionRequest;
import cn.wayuwa.wao.dto.NumberMergeGameDto;
import cn.wayuwa.wao.service.NumberMergeGameService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 游戏厅接口；当前先提供可持续存档的数字熔炉。 */
@RestController
@RequestMapping("/api/games/number-merge")
public class GameController {

    private static final String FALLBACK_OWNER = "wg";

    private final NumberMergeGameService game;
    private final CurrentMember currentMember;

    public GameController(NumberMergeGameService game, CurrentMember currentMember) {
        this.game = game;
        this.currentMember = currentMember;
    }

    @GetMapping
    public ApiResponse<NumberMergeGameDto> get(HttpServletRequest http) {
        return ApiResponse.ok(game.get(owner(http)));
    }

    @PostMapping("/move")
    public ApiResponse<NumberMergeGameDto> move(
            @Valid @RequestBody GameMoveRequest request, HttpServletRequest http) {
        return ApiResponse.ok(game.move(owner(http), request.direction(), request.revision()));
    }

    @PostMapping("/undo")
    public ApiResponse<NumberMergeGameDto> undo(
            @Valid @RequestBody GameRevisionRequest request, HttpServletRequest http) {
        return ApiResponse.ok(game.undo(owner(http), request.revision()));
    }

    @PostMapping("/restart")
    public ApiResponse<NumberMergeGameDto> restart(
            @Valid @RequestBody GameRevisionRequest request, HttpServletRequest http) {
        return ApiResponse.ok(game.restart(owner(http), request.revision()));
    }

    private String owner(HttpServletRequest http) {
        return currentMember.idOr(http, FALLBACK_OWNER);
    }
}
