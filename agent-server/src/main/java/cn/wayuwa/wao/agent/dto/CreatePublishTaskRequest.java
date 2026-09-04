package cn.wayuwa.wao.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新建一条发布任务。
 *
 * @param generateCopy  是否立即调用模型生成文案；模型未接入时会明确报错
 * @param sourceNotes   成员为本篇写下的想法或粘贴的对话摘录
 * @param useWaoContext 是否结合当前成员的 WAO 记忆、知识库、私人对话和过往成稿
 */
public record CreatePublishTaskRequest(@NotBlank(message = "请填写话题") @Size(max = 200) String topic,
                                       @NotBlank(message = "请选择平台") String platform,
                                       Boolean generateCopy,
                                       String sourceHotspotId,
                                       @Size(max = 20_000, message = "想法或对话摘录不能超过 20000 字") String sourceNotes,
                                       Boolean useWaoContext) {
}
