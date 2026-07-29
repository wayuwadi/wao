package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 管理员为新成员开账号的请求。 */
public record CreateAccountRequest(@NotBlank @Size(max = 64) String name,
                                   @NotBlank @Email String email,
                                   @NotBlank @Size(min = 8, max = 128) String password) {
}
