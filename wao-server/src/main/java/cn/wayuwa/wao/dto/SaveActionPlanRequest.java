package cn.wayuwa.wao.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** 保存长期目标与当前执行版本；成员身份只从服务端会话取得。 */
public record SaveActionPlanRequest(
        @NotBlank(message = "目标名称不能为空") @Size(max = 128, message = "目标名称不能超过 128 个字符") String title,
        @Size(max = 1000, message = "目标愿景不能超过 1000 个字符") String vision,
        LocalDate startDate,
        LocalDate targetDate,
        LocalTime reminderTime,
        boolean reminderEnabled,
        @NotEmpty(message = "至少安排一条可执行行动") @Size(max = 20, message = "一个计划最多 20 条行动")
        List<@Valid ActionPlanItemInput> items) { }
