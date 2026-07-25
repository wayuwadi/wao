package cn.wayuwa.wao.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class FeishuLoginServiceTest {
    private final FeishuLoginService service = new FeishuLoginService(
            new FeishuLoginProperties(true, "cli_test", "test-secret", "https://wao.example/api/auth/feishu/callback",
                    "https://wao.example", "https://open.feishu.cn"), RestClient.builder());

    @Test
    void createsSignedStateAndAcceptsItInCallback() {
        String authorizationUrl = service.authorizationUrl("workbench");
        String state = URLDecoder.decode(URI.create(authorizationUrl).getQuery().replaceFirst(".*state=", ""), StandardCharsets.UTF_8);

        assertThat(authorizationUrl).contains("app_id=cli_test");
        assertThat(service.callbackUri("auth-code", state).toString())
                .isEqualTo("https://wao.example/auth/feishu/callback?code=auth-code&state=" + state);
    }

    @Test
    void rejectsTamperedState() {
        assertThatThrownBy(() -> service.callbackUri("auth-code", "not-a-valid-state"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
