package cn.wayuwa.wao.dto;

/** WAO 内置一级菜单配置；路由由代码保证，其余展示和打开方式由注册表维护。 */
public record BuiltinMenuConfigDto(
        String id,
        String label,
        String icon,
        String group,
        int order,
        boolean visible,
        String openMode) {
}
