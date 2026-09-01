package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 已登录用户可查看的一条真实短信及其解析、转发状态。 */
public record VerificationMessageDto(String id, String phoneNumber, String sender, String source,
        String message, String code, LocalDateTime receivedAt, String forwardStatus, String forwardError,
        List<String> accountLabels) { }
