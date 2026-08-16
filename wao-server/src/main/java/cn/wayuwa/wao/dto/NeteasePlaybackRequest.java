package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

/** 官方要求的一首歌开始/结束播放回传；服务端补齐账号、设备与签名。 */
public record NeteasePlaybackRequest(
        @NotBlank String songId,
        @Pattern(regexp = "startplay|play") String action,
        @NotNull @PositiveOrZero Integer bitrate,
        @NotNull @PositiveOrZero Long startLogTime,
        @PositiveOrZero Double playedSeconds,
        @Pattern(regexp = "playend|interrupt|exception|ui") String end,
        String playlistId,
        String alg,
        Integer auditionStart,
        Integer auditionEnd) {
}
