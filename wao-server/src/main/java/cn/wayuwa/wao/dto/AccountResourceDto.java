package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;

/** 组织共享账号资源；列表不返回密码明文。 */
public record AccountResourceDto(String id, String platform, String accountLabel, String loginIdentifier,
        String phoneNumber, String notes, boolean enabled, boolean hasSecret, int revision,
        String createdBy, String updatedBy, LocalDateTime createdAt, LocalDateTime updatedAt) { }
