package cn.wayuwa.wao.dto;

import java.util.List;

/** 网易云红心歌单中的一首真实歌曲。 */
public record NeteaseMusicTrackDto(String id, String name, long duration, List<String> artists,
        String album, String coverUrl, boolean playable, boolean audition, String alg) {
}
