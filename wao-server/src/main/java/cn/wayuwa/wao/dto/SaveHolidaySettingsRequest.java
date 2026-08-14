package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 保存当前成员主动选择的国际节日类别。 */
public record SaveHolidaySettingsRequest(
        @NotNull @Size(max = 10, message = "国际节日类别选择过多")
        List<String> selectedInternationalCategories) { }
