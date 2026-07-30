package cn.wayuwa.wao.dto;

import java.util.List;

/** 外部应用菜单注册表；版本号为以后扩展字段保留升级空间。 */
public record ExternalAppRegistryDto(int version, List<ExternalAppConfigDto> apps) {
}
