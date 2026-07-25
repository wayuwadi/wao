package cn.wayuwa.wao.dto;

/**
 * 飞书通知 webhook 配置（对外表示）。地址脱敏，明文只在服务端发通知时用。
 *
 * @param urlMask 脱敏后的 webhook 地址
 * @param urlSet  是否已经配过地址
 * @param enabled 是否启用通知
 */
public record FeishuWebhookConfigDto(String urlMask, boolean urlSet, boolean enabled) {
}
