package cn.wayuwa.wao.dto;

import java.util.List;

/** 成员每日复盘及乐观锁版本；零版本表示尚未保存。 */
public record WorkdayRhythmReviewDto(long revision, List<String> entries) { }
