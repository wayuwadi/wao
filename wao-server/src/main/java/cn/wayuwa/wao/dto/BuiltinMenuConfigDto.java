package cn.wayuwa.wao.dto;

/** WAO 内置一级菜单配置；路由由代码保证，其余展示和打开方式由注册表维护。 */
public record BuiltinMenuConfigDto(
        String id,
        String label,
        String icon,
        String group,
        int order,
        boolean visible,
        String openMode,
        String placement) {

    /** 兼容既有调用和旧配置：缺少展示位置时仍作为完整菜单显示。 */
    public BuiltinMenuConfigDto(
            String id, String label, String icon, String group, int order, boolean visible, String openMode) {
        this(id, label, icon, group, order, visible, openMode, "MENU");
    }
}
