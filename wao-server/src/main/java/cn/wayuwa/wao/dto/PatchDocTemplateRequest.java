package cn.wayuwa.wao.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 模板管理中的可编辑名称、说明与正文快照；空字段保持原值。 */
public record PatchDocTemplateRequest(
        @Size(max = 128, message = "模板名称不能超过 128 个字符") String name,
        @Size(max = 512, message = "模板说明不能超过 512 个字符") String description,
        @Size(max = 5_000, message = "模板正文不能超过 5000 个块") List<DocBlockDto> blocks,
        RichDocContentDto content,
        @NotBlank(message = "模板版本不能为空")
        @Size(max = 40, message = "模板版本格式不合法") String expectedUpdatedAt) {
    public PatchDocTemplateRequest(String name, String description, List<DocBlockDto> blocks,
            String expectedUpdatedAt) {
        this(name, description, blocks, null, expectedUpdatedAt);
    }
}
