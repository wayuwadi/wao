package cn.wayuwa.wao.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 一段可独立审核、启停和调时长的视频分镜。 */
public record VideoSceneDto(
        @NotBlank @Pattern(regexp = "[a-z0-9-]{1,40}") String id,
        @Size(max = 40) String kicker,
        @NotBlank @Size(max = 80) String title,
        @NotBlank @Size(max = 500) String body,
        @Min(5) @Max(60) int seconds,
        @Pattern(regexp = "#[0-9A-Fa-f]{6}") String accent,
        boolean enabled) {
}
