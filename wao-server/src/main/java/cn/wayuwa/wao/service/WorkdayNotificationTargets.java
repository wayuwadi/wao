package cn.wayuwa.wao.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** 按通知类型解析成员目标，兼容未拆分的旧配置。 */
final class WorkdayNotificationTargets {
    private static final List<String> TARGET_FIELDS = List.of("channel", "webhookId", "webhookName", "chatId", "chatName");

    private WorkdayNotificationTargets() {}

    static JsonNode resolve(JsonNode notification, String targetField) {
        if (!notification.has(targetField) || !(notification instanceof ObjectNode object)) return notification;
        ObjectNode resolved = object.deepCopy();
        JsonNode target = notification.path(targetField);
        // 显式独立目标缺失/损坏时留空报错，绝不合并旧群 id 造成误发。
        for (String field : TARGET_FIELDS) resolved.set(field, target.path(field));
        return resolved;
    }
}
