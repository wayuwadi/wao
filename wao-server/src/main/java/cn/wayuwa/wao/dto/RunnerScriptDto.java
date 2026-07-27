package cn.wayuwa.wao.dto;

/** 下发给执行电脑的最新 Runner 脚本；hash 是内容字节的 SHA-256，节点写盘前先校验。 */
public record RunnerScriptDto(String hash, String content) {
}
