package cn.wayuwa.wao.dto;

import java.util.List;

/**
 * 飞书群聊同步配置（对外表示）。App Secret 只回脱敏形态，明文永不出服务端。
 *
 * @param appId        应用 App ID
 * @param appSecretMask 脱敏后的 App Secret，形如 {@code thg6••••••P78Z}
 * @param appSecretSet 是否已经配过 App Secret
 * @param chatId       目标群 id
 * @param chatName     目标群名称（展示用）
 * @param kbId         沉淀到哪个知识库
 * @param folderId     沉淀到哪个文件夹
 * @param senders      发言人 ID → 人名映射
 */
public record FeishuChatSyncConfigDto(
        String appId,
        String appSecretMask,
        boolean appSecretSet,
        String chatId,
        String chatName,
        String kbId,
        String folderId,
        List<FeishuSenderDto> senders) {
}
