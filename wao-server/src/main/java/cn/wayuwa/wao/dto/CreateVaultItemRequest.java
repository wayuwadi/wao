package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;

/** 新建密钥库条目请求。secret 明文只在本次请求中传输，落库前立即加密。 */
public record CreateVaultItemRequest(
        String groupId,
        @NotBlank String name,
        String icon,
        String user,
        String site,
        String note,
        String secret) {
}
