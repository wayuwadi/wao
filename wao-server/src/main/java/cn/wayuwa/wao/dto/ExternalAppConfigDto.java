package cn.wayuwa.wao.dto;

/** 一个可由系统设置注册、无需修改 WAO 代码的外部应用入口。 */
public record ExternalAppConfigDto(
        String id,
        String label,
        String icon,
        String group,
        int order,
        boolean visible,
        String appUrl,
        String healthUrl,
        String openMode) {
}
