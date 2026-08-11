package cn.wayuwa.wao.agent.dto;

/** WAO account/member 表提供给 Agent 的最小只读身份投影。 */
public record SharedAccountDto(String accountId, String displayName, String avatarText, String status) {
}
