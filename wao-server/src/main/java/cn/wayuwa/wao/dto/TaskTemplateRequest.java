package cn.wayuwa.wao.dto;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Personal snapshot containing task content only, with no assignees or comments. */
public record TaskTemplateRequest(
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Size(max = 200) String title,
        @NotNull @Size(max = 20000) String description,
        @NotNull @Size(max = 500) List<@Valid Step> steps) {
    public record Step(
            @NotBlank @Size(max = 64) String key,
            @Size(max = 64) String parentKey,
            @NotBlank @Size(max = 200) String title,
            @NotNull @Size(max = 20000) String description) {}
}
