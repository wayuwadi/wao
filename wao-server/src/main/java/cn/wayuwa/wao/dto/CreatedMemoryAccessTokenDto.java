package cn.wayuwa.wao.dto;

/** 新建 MCP 令牌的单次明文响应；刷新后无法再次读取。 */
public record CreatedMemoryAccessTokenDto(
        MemoryAccessTokenDto token,
        String secret) {
}
