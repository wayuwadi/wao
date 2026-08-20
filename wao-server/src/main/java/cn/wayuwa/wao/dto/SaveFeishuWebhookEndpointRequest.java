package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 新增或编辑飞书自定义机器人。编辑时 url 为 null/空白表示保留原密钥，避免前端读回明文。
 */
public record SaveFeishuWebhookEndpointRequest(
        @NotBlank(message = "配置名称不能为空")
        @Size(max = 64, message = "配置名称不能超过 64 个字符")
        String name,
        @Size(max = 255, message = "用途说明不能超过 255 个字符")
        String description,
        @Size(max = 1024, message = "Webhook 地址过长")
        String url,
        @NotNull(message = "请选择是否启用")
        Boolean enabled,
        @NotNull(message = "请选择是否作为系统默认通知")
        Boolean systemDefault) {
}
