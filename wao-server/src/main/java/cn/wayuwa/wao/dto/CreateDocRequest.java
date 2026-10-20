package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 新建文档请求：必须指定归属的知识库与文件夹（文档与知识库的关联在创建时建立）。
 *
 * <p>作者不在请求体里 —— 由服务端从当前会话推导，避免任何人都能冒名写入。
 */
public record CreateDocRequest(
        @NotBlank String kbId,
        @NotBlank String folderId,
        String title,
        String templateId,
        @Size(max = 5000) List<DocBlockDto> blocks,
        RichDocContentDto content,
        String pageType) {
    public CreateDocRequest(String kbId, String folderId, String title, String templateId, List<DocBlockDto> blocks, RichDocContentDto content) {
        this(kbId, folderId, title, templateId, blocks, content, "document");
    }

    public CreateDocRequest(String kbId, String folderId, String title) {
        this(kbId, folderId, title, null, null, null);
    }

    public CreateDocRequest(String kbId, String folderId, String title, String templateId) {
        this(kbId, folderId, title, templateId, null, null);
    }

    public CreateDocRequest(String kbId, String folderId, String title, String templateId,
            List<DocBlockDto> blocks) {
        this(kbId, folderId, title, templateId, blocks, null);
    }
}
