package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 当前账号绑定邮箱、首次设置密码或修改密码的请求。 */
public record EmailCredentialRequest(@NotBlank @Email String email,
                                     String currentPassword,
                                     @NotBlank @Size(min = 8, max = 128) String newPassword) {
}
