package cn.wayuwa.wao.dto;

/** 共享聊天当前飞书目标；群必须来自自建应用真实所在群列表。 */
public record SharedChatConfigDto(String chatId, String chatName, boolean configured,
        int pollIntervalSeconds) {
}
