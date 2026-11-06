package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TaskTemplateCreateRequest(
        @NotBlank @Size(max = 64) String accountId,
        @NotBlank @Size(max = 32) String templateId,
        @NotNull @Size(max = 64) String projectId) {}
