package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** 邮箱密码登录请求。 */
public record EmailLoginRequest(@NotBlank @Email String email, @NotBlank String password) {
}
