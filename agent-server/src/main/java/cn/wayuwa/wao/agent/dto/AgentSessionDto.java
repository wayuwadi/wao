package cn.wayuwa.wao.agent.dto;

/** 从 WAO 共享会话解析出的账号与 Agent 工作区信息。 */
public record AgentSessionDto(String accountId, String displayName, String avatarText,
                              String workspaceId, String workspaceName, String role, boolean demoContent) {
}
