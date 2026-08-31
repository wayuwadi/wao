package cn.wayuwa.wao.dto;

/** Runner 从本次新提交中提取的可核对 Conventional Commit 信息。 */
public record FunctionIterationInput(String commitSha, String scope, String subject) {}
