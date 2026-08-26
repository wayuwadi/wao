package cn.wayuwa.wao.dto;

/** 保存共享聊天飞书群目标。空 id 表示明确停用飞书桥接。 */
public record SaveSharedChatConfigRequest(String chatId) {
}
