package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 开发期邮箱注册请求。 */
public record EmailRegisterRequest(@NotBlank @Email String email,
                                   @NotBlank @Size(min = 8, max = 128) String password,
                                   @NotBlank @Size(max = 64) String name) {
}
