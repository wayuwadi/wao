package cn.wayuwa.wao.dto;

/** Codex 设备码登录的当前状态；完成时同时返回新建的账号。 */
public record AiAccountLoginStatusDto(String status, String message, AiAccountDto account) {
}
