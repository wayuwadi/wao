package cn.wayuwa.wao.dto;

/**
 * 保存飞书通知 webhook。{@code url} 为 null 表示不修改地址，只改启用开关。
 *
 * @param url     webhook 地址明文
 * @param enabled 是否启用
 */
public record SaveWebhookRequest(String url, Boolean enabled) {
}
