package cn.wayuwa.wao.dto;

/** 局部更新密钥库条目请求。空字段不改；secret 非空时重新加密覆盖。 */
public record PatchVaultItemRequest(
        String name,
        String icon,
        String user,
        String site,
        String note,
        String secret,
        String groupId) {
}
