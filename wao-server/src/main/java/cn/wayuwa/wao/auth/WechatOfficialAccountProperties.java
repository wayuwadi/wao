package cn.wayuwa.wao.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/** 微信公众号扫码验证码登录配置。 */
@ConfigurationProperties("wechat.official-account")
public record WechatOfficialAccountProperties(boolean enabled, String appId, String appSecret, String token,
                                               String apiBaseUrl, String qrBaseUrl) {

    /** 判断公众号登录所需的真实开放接口配置是否完整。 */
    public boolean configured() {
        return enabled && StringUtils.hasText(appId) && StringUtils.hasText(appSecret)
                && StringUtils.hasText(token) && StringUtils.hasText(apiBaseUrl) && StringUtils.hasText(qrBaseUrl);
    }
}
