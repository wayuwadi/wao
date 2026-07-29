package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 管理员重置成员账号密码的请求。 */
public record ResetMemberPasswordRequest(@NotBlank @Size(min = 8, max = 128) String password) {
}
