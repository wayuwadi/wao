package cn.wayuwa.wao.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 整体保存外部应用菜单注册表。 */
public record SaveExternalAppRegistryRequest(
        @NotNull(message = "应用列表不能为空")
        @Size(max = 30, message = "外部应用不能超过 30 个")
        List<@Valid SaveExternalAppConfigRequest> apps) {
}
