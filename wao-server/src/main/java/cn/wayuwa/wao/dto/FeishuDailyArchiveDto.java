package cn.wayuwa.wao.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 每日群归档的真实配置与运行证据，不包含凭据或消息正文。 */
public record FeishuDailyArchiveDto(boolean enabled, String timezone, String schedule,
        LocalDateTime nextRunAt, boolean configured, String appId, String chatId,
        String chatName, String kbId, Run latestRun) {
    public record Run(LocalDate archiveDate, String status, LocalDateTime runAt,
            Integer durationMs, String errorCode, String docId, Integer messageCount) { }
}
