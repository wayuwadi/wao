package cn.wayuwa.wao.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Bind a write to the account that prepared its local draft. */
public record TaskTemplateWriteRequest(@NotBlank String accountId, @NotNull @Valid TaskTemplateRequest content) {}
