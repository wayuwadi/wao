package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/** 整体保存内置菜单注册表。 */
public record SaveBuiltinMenuRegistryRequest(@NotNull List<BuiltinMenuConfigDto> menus) {
}
