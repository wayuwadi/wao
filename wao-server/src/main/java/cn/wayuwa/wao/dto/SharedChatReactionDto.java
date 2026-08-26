package cn.wayuwa.wao.dto;

import java.util.List;

/** 一种表情的真实聚合；reacted 表示当前 WAO 成员是否点过。 */
public record SharedChatReactionDto(
        String emojiType,
        String emoji,
        int count,
        boolean reacted,
        List<String> names) {
}
