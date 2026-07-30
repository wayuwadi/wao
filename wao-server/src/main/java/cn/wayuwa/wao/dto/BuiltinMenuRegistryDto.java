package cn.wayuwa.wao.dto;

import java.util.List;

/** 内置菜单注册表。 */
public record BuiltinMenuRegistryDto(int version, List<BuiltinMenuConfigDto> menus) {
}
