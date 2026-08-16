package cn.wayuwa.wao.controller;

import java.net.URI;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.NeteaseMusicAuthorizeDto;
import cn.wayuwa.wao.dto.NeteaseMusicPlayUrlDto;
import cn.wayuwa.wao.dto.NeteaseMusicStatusDto;
import cn.wayuwa.wao.dto.NeteaseMusicTrackPageDto;
import cn.wayuwa.wao.dto.NeteasePlaybackRequest;
import cn.wayuwa.wao.service.NeteaseMusicService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/music/netease")
public class NeteaseMusicController {
    private final NeteaseMusicService service;
    private final CurrentMember currentMember;

    public NeteaseMusicController(NeteaseMusicService service, CurrentMember currentMember) {
        this.service = service;
        this.currentMember = currentMember;
    }

    @GetMapping("/status")
    public ApiResponse<NeteaseMusicStatusDto> status(HttpServletRequest request) {
        return ApiResponse.ok(service.status(currentMember.id(request)));
    }

    @PostMapping("/authorize")
    public ApiResponse<NeteaseMusicAuthorizeDto> authorize(HttpServletRequest request) {
        return ApiResponse.ok(service.authorize(currentMember.id(request), netStatus(request)));
    }

    /** 官方 H5 OAuth 回调；state 已绑定成员，成功和失败都只重定向固定前端状态。 */
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code, @RequestParam String state,
            HttpServletRequest request) {
        URI destination;
        try {
            destination = service.callback(code, state, clientIp(request));
        } catch (RuntimeException ex) {
            destination = service.frontendResult("failed");
        }
        return ResponseEntity.status(302).header(HttpHeaders.LOCATION, destination.toString()).build();
    }

    @DeleteMapping("/binding")
    public ApiResponse<Void> unbind(HttpServletRequest request) {
        service.unbind(currentMember.id(request));
        return ApiResponse.ok(null);
    }

    @GetMapping("/liked-songs")
    public ApiResponse<NeteaseMusicTrackPageDto> likedSongs(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "30") int limit,
            HttpServletRequest request) {
        return ApiResponse.ok(service.likedSongs(currentMember.id(request), offset, limit, clientIp(request), netStatus(request)));
    }

    @GetMapping("/songs/{songId}/play-url")
    public ApiResponse<NeteaseMusicPlayUrlDto> playUrl(@PathVariable String songId, HttpServletRequest request) {
        return ApiResponse.ok(service.playUrl(currentMember.id(request), songId, clientIp(request), netStatus(request)));
    }

    @PostMapping("/playback")
    public ApiResponse<Void> playback(@Valid @RequestBody NeteasePlaybackRequest body, HttpServletRequest request) {
        service.reportPlayback(currentMember.id(request), body, clientIp(request), netStatus(request));
        return ApiResponse.ok(null);
    }

    static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String candidate = forwarded == null ? request.getRemoteAddr() : forwarded.split(",", 2)[0].trim();
        return candidate != null && candidate.length() <= 64 && candidate.matches("[0-9a-fA-F:.]+")
                ? candidate : "127.0.0.1";
    }

    static String netStatus(HttpServletRequest request) {
        String value = request.getHeader("X-WAO-Net-Status");
        return value != null && value.matches("wifi|2g|3g|4g|5g") ? value : "wifi";
    }
}
