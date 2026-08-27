package cn.wayuwa.wao.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.wayuwa.wao.dto.AccountIdentityDto;
import cn.wayuwa.wao.dto.AuthSessionDto;
import cn.wayuwa.wao.dto.MemberDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class JumpServerSsoServiceTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-08-27T08:00:00Z");
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void signsShortLivedAdministratorTicketWithServerDerivedFields() throws Exception {
        JumpServerSsoService service = service(true, SECRET, Duration.ofSeconds(45));

        String url = service.loginUrl(session("m-1", "娃弟", "管理员", "wadi@example.com"));
        String token = url.substring(url.indexOf("token=") + 6);
        String[] parts = token.split("\\.");
        Map<String, Object> payload = json.readValue(
                Base64.getUrlDecoder().decode(parts[0]), new TypeReference<>() { });

        assertThat(url).startsWith(
                "https://jms.wayuwa.cn/api/v1/authentication/custom-sso/login/?token=");
        assertThat(parts).hasSize(2);
        assertThat(parts[1]).isEqualTo(sign(parts[0]));
        assertThat(payload).containsEntry("aud", "jumpserver")
                .containsEntry("sub", "m-1")
                .containsEntry("name", "娃弟")
                .containsEntry("email", "wadi@example.com")
                .containsEntry("systemRole", "SystemAdmin");
        assertThat(((Number) payload.get("iat")).longValue()).isEqualTo(NOW.getEpochSecond());
        assertThat(((Number) payload.get("exp")).longValue()).isEqualTo(NOW.plusSeconds(45).getEpochSecond());
        assertThat(payload.get("nonce")).asString().isNotBlank();
    }

    @Test
    void mapsMemberToSystemUserAndUsesNonSecretFallbackEmail() throws Exception {
        String url = service(true, SECRET, Duration.ofSeconds(45))
                .loginUrl(session("m-2", "成员", "成员", null));
        String encoded = url.substring(url.indexOf("token=") + 6).split("\\.")[0];
        Map<String, Object> payload = json.readValue(
                Base64.getUrlDecoder().decode(encoded), new TypeReference<>() { });

        assertThat(payload).containsEntry("systemRole", "SystemUser")
                .containsEntry("email", "m-2@wao.local");
    }

    @Test
    void refusesDisabledWeakOrLongLivedConfiguration() {
        AuthSessionDto member = session("m-1", "成员", "成员", null);

        assertThatThrownBy(() -> service(false, SECRET, Duration.ofSeconds(45)).loginUrl(member))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("尚未启用");
        assertThatThrownBy(() -> service(true, "too-short", Duration.ofSeconds(45)).loginUrl(member))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("共享密钥");
        assertThatThrownBy(() -> service(true, SECRET, Duration.ofMinutes(3)).loginUrl(member))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("期限");
    }

    private JumpServerSsoService service(boolean enabled, String secret, Duration ttl) {
        var properties = new JumpServerSsoProperties(enabled, secret,
                "https://jms.wayuwa.cn/api/v1/authentication/custom-sso/login/", ttl);
        return new JumpServerSsoService(properties, json, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private AuthSessionDto session(String memberId, String name, String role, String email) {
        List<AccountIdentityDto> identities = email == null
                ? List.of()
                : List.of(new AccountIdentityDto("email", email, name));
        return new AuthSessionDto("account", new MemberDto(memberId, name, "成", "#4358D6", role),
                identities, LocalDateTime.of(2026, 8, 27, 8, 0));
    }

    private String sign(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII)));
    }
}
