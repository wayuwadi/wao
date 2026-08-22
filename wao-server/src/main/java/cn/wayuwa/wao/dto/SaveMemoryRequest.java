package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

/** 创建记忆文档或修订的统一输入。 */
public record SaveMemoryRequest(
        @NotBlank(message = "记忆范围不能为空") String scopeType,
        String scopeId,
        @NotBlank(message = "记忆分类不能为空") String category,
        @NotBlank(message = "记忆标题不能为空") @Size(max = 200, message = "记忆标题不能超过 200 字") String title,
        @NotBlank(message = "记忆正文不能为空") @Size(max = 20_000, message = "记忆正文不能超过 20000 字") String content,
        @Size(max = 1000, message = "变更说明不能超过 1000 字") String changeSummary,
        List<String> evidenceLinks,
        LocalDateTime expiresAt,
        String baseRevisionId) { }
