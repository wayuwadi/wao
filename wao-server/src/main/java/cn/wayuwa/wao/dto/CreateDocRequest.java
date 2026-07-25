package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;

/** 新建文档请求：必须指定归属的知识库与文件夹（文档与知识库的关联在创建时建立）。 */
public record CreateDocRequest(
        @NotBlank String kbId,
        @NotBlank String folderId,
        String title,
        String author) {
}
