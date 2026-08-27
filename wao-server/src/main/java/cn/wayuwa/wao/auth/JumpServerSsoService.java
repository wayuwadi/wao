package cn.wayuwa.wao.auth;

import cn.wayuwa.wao.dto.AuthSessionDto;
import cn.wayuwa.wao.dto.MemberDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

/** 为已验证的 WAO 成员签发 JumpServer CE Custom SSO 一次性登录票据。 */
@Service
public class JumpServerSsoService {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String ADMIN_ROLE = "SystemAdmin";
    private static final String MEMBER_ROLE = "SystemUser";
    private static final Duration MAX_TICKET_TTL = Duration.ofMinutes(2);

    private final JumpServerSsoProperties properties;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    public JumpServerSsoService(JumpServerSsoProperties properties, ObjectMapper json) {
        this(properties, json, Clock.systemUTC());
    }

    JumpServerSsoService(JumpServerSsoProperties properties, ObjectMapper json, Clock clock) {
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    /**
     * 为当前 WAO 会话生成固定受众、短时有效的 JumpServer 登录 URL。
     *
     * @param session 已由服务端 Session 验证的账号资料
     * @return 只指向配置中固定 JumpServer Custom SSO 端点的 URL
     */
    public String loginUrl(AuthSessionDto session) {
        validateConfiguration();
        Instant issuedAt = clock.instant();
        Duration ttl = properties.ticketTtl();
        MemberDto member = session.member();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v", 1);
        payload.put("aud", "jumpserver");
        payload.put("sub", member.id());
        payload.put("name", member.name());
        payload.put("email", session.identities().stream()
                .map(identity -> identity.email())
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(member.id() + "@wao.local"));
        payload.put("systemRole", isAdmin(member.role()) ? ADMIN_ROLE : MEMBER_ROLE);
        payload.put("iat", issuedAt.getEpochSecond());
        payload.put("exp", issuedAt.plus(ttl).getEpochSecond());
        payload.put("nonce", UUID.randomUUID().toString());

        String encodedPayload = encodeJson(payload);
        String token = encodedPayload + "." + sign(encodedPayload);
        return UriComponentsBuilder.fromUriString(properties.loginUrl())
                .replaceQuery(null)
                .queryParam("token", token)
                .build(true)
                .toUriString();
    }

    private void validateConfiguration() {
        if (!properties.enabled()) {
            throw new IllegalStateException("JumpServer 单点登录尚未启用");
        }
        if (!StringUtils.hasText(properties.sharedSecret()) || properties.sharedSecret().length() < 32) {
            throw new IllegalStateException("JumpServer 单点登录共享密钥未配置");
        }
        if (!StringUtils.hasText(properties.loginUrl())
                || !properties.loginUrl().startsWith("https://jms.wayuwa.cn/")) {
            throw new IllegalStateException("JumpServer 单点登录地址不正确");
        }
        if (properties.ticketTtl() == null || properties.ticketTtl().isZero()
                || properties.ticketTtl().isNegative() || properties.ticketTtl().compareTo(MAX_TICKET_TTL) > 0) {
            throw new IllegalStateException("JumpServer 单点登录票据期限不正确");
        }
    }

    private String encodeJson(Map<String, Object> payload) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(payload));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("JumpServer 单点登录票据序列化失败", exception);
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(properties.sharedSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception exception) {
            throw new IllegalStateException("JumpServer 单点登录票据签名失败", exception);
        }
    }

    private static boolean isAdmin(String role) {
        return "管理员".equals(role) || "超级管理员".equals(role);
    }
}
