package cn.wayuwa.wao.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

/** 执行节点首次配对请求；engines 声明本机可用的 AI CLI。 */
public record PairAiRunnerRequest(
        @NotBlank String code,
        @NotBlank String name,
        String platform,
        List<String> engines,
        Integer maxConcurrency) {
}
