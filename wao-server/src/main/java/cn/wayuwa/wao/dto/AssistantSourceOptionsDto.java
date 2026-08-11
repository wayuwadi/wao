package cn.wayuwa.wao.dto;

import java.time.LocalDate;
import java.util.List;

/** 当前可检索的 WAO 资产来源及聊天筛选元数据。 */
public record AssistantSourceOptionsDto(
        List<AssistantSourceOptionDto> chats,
        List<String> senders,
        List<AssistantSourceOptionDto> projects,
        LocalDate earliestChatDate,
        LocalDate latestChatDate) {
}
