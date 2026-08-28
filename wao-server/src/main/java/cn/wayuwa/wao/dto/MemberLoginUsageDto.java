package cn.wayuwa.wao.dto;

import java.time.OffsetDateTime;

/** 一名真实成员从通知中心发布后累计的登录频率。 */
public record MemberLoginUsageDto(String memberId, String memberName, String color,
        long count7Days, long count30Days, OffsetDateTime lastLoginAt) { }
