package cn.wayuwa.wao.dto;

/** 明文只在签发响应中出现一次。 */
public record CreatedWorkspaceMcpTokenDto(WorkspaceMcpTokenDto token, String secret) { }
