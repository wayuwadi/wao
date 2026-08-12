package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.Size;

/** 保存当天事实复盘；空正文表示删除当天复盘。 */
public record SaveActionNoteRequest(
        @Size(max = 2000, message = "复盘不能超过 2000 个字符") String content) { }
