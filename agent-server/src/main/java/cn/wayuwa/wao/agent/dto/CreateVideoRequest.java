package cn.wayuwa.wao.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateVideoRequest(
        @NotBlank(message = "请填写视频提示词")
        @Size(max = 32000, message = "视频提示词不能超过 32000 个字符") String prompt,
        @NotBlank(message = "请选择视频模型") String model,
        @NotNull(message = "请选择视频时长") Integer seconds,
        @NotBlank(message = "请选择视频画幅") String size) { }
