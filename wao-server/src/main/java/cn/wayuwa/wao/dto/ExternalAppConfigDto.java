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
        String openMode,
        String placement) {

    /** 兼容既有调用和旧配置：缺少展示位置时仍作为完整菜单显示。 */
    public ExternalAppConfigDto(
            String id, String label, String icon, String group, int order, boolean visible,
            String appUrl, String healthUrl, String openMode) {
        this(id, label, icon, group, order, visible, appUrl, healthUrl, openMode, "MENU");
    }
}
