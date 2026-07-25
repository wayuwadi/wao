package cn.wayuwa.wao.dto;

import java.util.List;

/**
 * 保存飞书群聊同步配置。字段为 null 表示不修改 —— App Secret 留空即保留原值，
 * 用户不必每次保存都重新粘一遍。
 */
public record SaveChatSyncConfigRequest(
        String appId,
        String appSecret,
        String chatId,
        String chatName,
        String kbId,
        String folderId,
        List<FeishuSenderDto> senders) {
}
