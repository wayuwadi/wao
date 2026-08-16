package cn.wayuwa.wao.dto;

import java.util.List;

public record NeteaseMusicTrackPageDto(List<NeteaseMusicTrackDto> items, int total, int offset, int limit,
        String playlistId, String playlistName) {
}
