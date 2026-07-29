package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 保存自媒体 Agent 一级菜单配置。 */
public record SaveMediaAgentMenuConfigRequest(
        @NotBlank(message = "菜单名称不能为空")
        @Size(max = 24, message = "菜单名称不能超过 24 个字符")
        String label,
        @NotBlank(message = "请选择所属分组")
        @Pattern(regexp = "PUBLIC|PERSONAL|TOOLS", message = "所属分组不正确")
        String group,
        @NotNull(message = "请填写排序值")
        @Min(value = 0, message = "排序值不能小于 0")
        @Max(value = 999, message = "排序值不能大于 999")
        Integer order,
        @NotNull(message = "请选择是否显示")
        Boolean visible) {
}
