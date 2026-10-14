package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/** 提升子事项前的完整快照；用于拒绝覆盖并发修改。 */
public record PromoteSubtaskRequest(@NotNull List<Step> expectedSourceSubs) {}
