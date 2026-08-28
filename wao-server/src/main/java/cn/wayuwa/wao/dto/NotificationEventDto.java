package cn.wayuwa.wao.dto;

import java.time.OffsetDateTime;

/** 通知中心一条可追溯到真实业务表的事件。 */
public record NotificationEventDto(String id, String type, String title, String detail,
        String actorName, OffsetDateTime occurredAt, String targetPath, String severity) { }
