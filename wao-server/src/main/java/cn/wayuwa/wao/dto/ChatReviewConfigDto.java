package cn.wayuwa.wao.dto;

import java.util.List;

/** 共享聊天复盘配置：唯一群聊来自现有同步配置，用户只固定两位发言人。 */
public record ChatReviewConfigDto(
        boolean chatConfigured,
        boolean configured,
        String chatId,
        String chatName,
        List<ChatReviewSpeakerDto> availableSpeakers,
        List<ChatReviewSpeakerDto> selectedSpeakers,
        String message) { }
