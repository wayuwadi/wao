package cn.wayuwa.wao.agent.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 保存完整分镜；version 用于阻止另一标签页的旧稿覆盖新稿。 */
public record UpdateVideoProjectRequest(
        @Min(1) int version,
        @NotBlank @Size(max = 200) String title,
        @NotEmpty @Size(min = 3, max = 12) List<@Valid VideoSceneDto> scenes) {
}
