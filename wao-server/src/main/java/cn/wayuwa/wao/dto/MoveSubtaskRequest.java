package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** 移动整棵子树；期望列表用于拒绝覆盖移动前已发生的并发编辑。 */
public record MoveSubtaskRequest(@NotBlank String targetTaskId, String parentId,
        @NotNull List<Step> expectedSourceSubs, @NotNull List<Step> expectedTargetSubs) {}
