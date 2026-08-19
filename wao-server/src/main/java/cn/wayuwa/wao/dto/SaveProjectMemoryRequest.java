package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 人工创建或修改项目工程记忆。 */
public record SaveProjectMemoryRequest(
        @NotBlank @Size(max = 24) String category,
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 5000) String content,
        @Size(max = 10) List<@Size(max = 1024) String> evidenceLinks) {
}
