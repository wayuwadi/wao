package cn.wayuwa.wao.dto;

import java.util.List;

public record CompanionActivitySnapshotDto(String status, int unreadCount, String serverTime,
        List<CompanionActivityDto> activities) { }
