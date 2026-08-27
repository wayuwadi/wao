package cn.wayuwa.wao.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** JumpServer Custom SSO 的固定入口、短时票据期限和主节点私有共享密钥。 */
@ConfigurationProperties("wao.auth.jumpserver")
public record JumpServerSsoProperties(boolean enabled, String sharedSecret, String loginUrl, Duration ticketTtl) {
}
