package cn.wayuwa.wao.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 爬虫按手机号取得的最近有效验证码。 */
public record LatestVerificationCodeDto(String phoneNumber, String code, String sender, String source,
        LocalDateTime receivedAt, List<String> accountLabels) { }
