package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 工作区 MCP 设备令牌签发请求。 */
public record CreateWorkspaceMcpTokenRequest(@NotBlank @Size(max = 80) String name,
        Boolean canWrite, Boolean canPublishHostedPage, List<String> permissions,
        List<String> projectIds, List<String> knowledgeBaseIds, String clientType) {
    public CreateWorkspaceMcpTokenRequest(String name, Boolean canWrite, Boolean canPublishHostedPage) {
        this(name, canWrite, canPublishHostedPage, null, null, null, null);
    }
}
