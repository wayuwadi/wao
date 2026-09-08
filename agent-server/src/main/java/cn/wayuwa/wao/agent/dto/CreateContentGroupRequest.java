package cn.wayuwa.wao.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 用同一份真实材料创建抖音、小红书和公众号三端内容。
 *
 * @param topic          成员输入的创作标题或主题
 * @param sourceNotes    成员提供的参考内容或对话摘录
 * @param useWaoContext  是否结合当前成员有权读取的 WAO 内容
 */
public record CreateContentGroupRequest(
        @NotBlank(message = "请填写创作标题") @Size(max = 200) String topic,
        @Size(max = 20_000, message = "参考内容不能超过 20000 字") String sourceNotes,
        Boolean useWaoContext) {
}
