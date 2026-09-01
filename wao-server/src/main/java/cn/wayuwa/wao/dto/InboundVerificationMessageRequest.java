package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/** 手机短信转发器提交的标准消息；externalId 可用于跨重试幂等。 */
public record InboundVerificationMessageRequest(
        @NotBlank(message = "手机号不能为空") @Size(max = 32, message = "手机号不能超过 32 个字符") String phoneNumber,
        @Size(max = 128, message = "发送方不能超过 128 个字符") String sender,
        @Size(max = 128, message = "来源不能超过 128 个字符") String source,
        @NotBlank(message = "短信正文不能为空") @Size(max = 4096, message = "短信正文不能超过 4096 个字符") String message,
        @Size(max = 256, message = "外部消息 id 不能超过 256 个字符") String externalId,
        LocalDateTime receivedAt) { }
