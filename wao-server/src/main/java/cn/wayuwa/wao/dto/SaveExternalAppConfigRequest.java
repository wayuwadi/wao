package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 保存一条外部应用菜单配置。 */
public record SaveExternalAppConfigRequest(
        @NotBlank(message = "应用标识不能为空")
        @Size(max = 40, message = "应用标识不能超过 40 个字符")
        @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*", message = "应用标识只能使用小写字母、数字和中划线")
        String id,
        @NotBlank(message = "菜单名称不能为空")
        @Size(max = 24, message = "菜单名称不能超过 24 个字符")
        String label,
        @NotBlank(message = "菜单图标不能为空")
        @Size(max = 64, message = "菜单图标不能超过 64 个字符")
        @Pattern(regexp = "ri-[a-z0-9-]+-(?:line|fill)", message = "菜单图标必须是有效的 Remix Icon 类名")
        String icon,
        @NotBlank(message = "请选择所属分组")
        @Pattern(regexp = "PUBLIC|PERSONAL|TOOLS", message = "所属分组不正确")
        String group,
        @NotNull(message = "请填写排序值")
        @Min(value = 0, message = "排序值不能小于 0")
        @Max(value = 999, message = "排序值不能大于 999")
        Integer order,
        @NotNull(message = "请选择是否显示")
        Boolean visible,
        @NotBlank(message = "应用入口不能为空")
        @Size(max = 255, message = "应用入口不能超过 255 个字符")
        String appUrl,
        @Size(max = 255, message = "健康检查地址不能超过 255 个字符")
        String healthUrl) {
}
