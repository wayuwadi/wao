package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;

/** 执行节点首次配对请求。 */
public record PairAiRunnerRequest(
        @NotBlank String code,
        @NotBlank String name,
        String platform,
        Integer maxConcurrency) {
}
