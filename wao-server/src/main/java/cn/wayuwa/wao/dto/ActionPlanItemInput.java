package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 新建或调整一条周期行动。 */
public record ActionPlanItemInput(
        String id,
        @NotBlank(message = "行动名称不能为空") @Size(max = 128, message = "行动名称不能超过 128 个字符") String title,
        @NotEmpty(message = "每条行动至少选择一个星期") @Size(max = 7) List<Integer> weekdays) { }
