package cn.wayuwa.wao.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新建一条发布任务。
 *
 * @param generateCopy 是否立即调用模型生成文案；模型未接入时会明确报错
 */
public record CreatePublishTaskRequest(@NotBlank(message = "请填写话题") @Size(max = 200) String topic,
                                       @NotBlank(message = "请选择平台") String platform,
                                       Boolean generateCopy,
                                       String sourceHotspotId) {
}
