package cn.wayuwa.wao.dto;

/** 后台允许用户在一次 Codex AI Run 中选择的模型。 */
public record AiRunModelOptionDto(String id, String label, String description) { }
