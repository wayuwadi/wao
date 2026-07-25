package cn.wayuwa.wao.dto;

/** 节点配对结果；token 仅返回一次，由节点保存到本机。 */
public record PairedAiRunnerDto(String runnerId, String token) {
}
