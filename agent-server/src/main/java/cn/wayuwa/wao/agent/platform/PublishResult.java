package cn.wayuwa.wao.agent.platform;

/**
 * 平台返回的发布结果。
 *
 * @param platformItemId 平台侧内容 id（抖音是 item_id）
 * @param credential     发布过程中如果刷新了令牌，这里回传新凭据，否则为 null
 */
public record PublishResult(String platformItemId, PlatformCredential credential) {
}
