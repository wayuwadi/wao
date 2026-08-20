package cn.wayuwa.wao.dto;

import java.util.List;

/** AI Run 单次派工的受控模型参数；Fast 固定关闭。 */
public record AiRunOptionsDto(
        List<AiRunModelOptionDto> codexModels,
        List<AiRunReasoningOptionDto> reasoningEfforts,
        String defaultCodexModel,
        String defaultReasoningEffort,
        String serviceTier) { }
