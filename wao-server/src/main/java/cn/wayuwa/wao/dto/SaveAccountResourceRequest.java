package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 创建或更新共享账号；更新时 revision 阻止静默覆盖，secret 留空表示保留旧密码。 */
public record SaveAccountResourceRequest(
        @NotBlank(message = "平台不能为空") @Size(max = 64, message = "平台不能超过 64 个字符") String platform,
        @NotBlank(message = "账号名称不能为空") @Size(max = 128, message = "账号名称不能超过 128 个字符") String accountLabel,
        @Size(max = 256, message = "登录账号不能超过 256 个字符") String loginIdentifier,
        @Size(max = 32, message = "手机号不能超过 32 个字符") String phoneNumber,
        @Size(max = 1024, message = "备注不能超过 1024 个字符") String notes,
        @Size(max = 1024, message = "密码不能超过 1024 个字符") String secret,
        boolean enabled,
        Integer revision) { }
