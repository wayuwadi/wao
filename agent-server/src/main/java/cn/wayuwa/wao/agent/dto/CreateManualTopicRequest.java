package cn.wayuwa.wao.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 成员手动记录到话题池的灵感。 */
public record CreateManualTopicRequest(
        @NotBlank(message = "请填写话题") @Size(max = 200) String topic,
        @NotBlank(message = "请选择目标平台") String platform) {
}
