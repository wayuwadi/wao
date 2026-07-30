package cn.wayuwa.wao.dto;

/** WAO 内置一级菜单配置；路由由代码保证，名称、图标、分组、顺序和显隐由注册表维护。 */
public record BuiltinMenuConfigDto(
        String id,
        String label,
        String icon,
        String group,
        int order,
        boolean visible) {
}
