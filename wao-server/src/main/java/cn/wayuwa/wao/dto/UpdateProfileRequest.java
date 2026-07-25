package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 当前账号的成员资料修改请求。 */
public record UpdateProfileRequest(
        @NotBlank @Size(max = 64) String name,
        @NotBlank @Size(max = 4) String initial) {
}
