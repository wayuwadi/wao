package cn.wayuwa.wao.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 聊天同步运行的真实结果；contentBytesAdded 不冒充数据库页或文件系统的精确分配量。 */
public record SharedChatSyncRunDto(String id, String triggerType, String chatName, LocalDate syncDay,
        String status, int messagesScanned, int messagesInserted, int messagesUpdated,
        long contentBytesAdded, String errorMessage, LocalDateTime startedAt, LocalDateTime finishedAt) {
}
