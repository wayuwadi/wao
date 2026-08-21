package cn.wayuwa.wao.dto;

public record CompanionActivityDto(String id, String status, String title, String projectName,
        String message, String occurredAt, String deepLink, boolean unread) { }
