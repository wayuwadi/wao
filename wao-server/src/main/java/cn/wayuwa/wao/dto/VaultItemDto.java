package cn.wayuwa.wao.dto;

/** 密钥库条目对外表示。secret 从不出现在这里；是否已设置密码由 hasSecret 表示，需查看时另调 reveal 接口。 */
public record VaultItemDto(
        String id,
        String name,
        String icon,
        String user,
        String site,
        String note,
        String updated,
        boolean hasSecret) {
}
