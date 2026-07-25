package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;

/** 首次第三方登录时认领既有成员。 */
public record ClaimAccountRequest(@NotBlank String memberId) {
}
