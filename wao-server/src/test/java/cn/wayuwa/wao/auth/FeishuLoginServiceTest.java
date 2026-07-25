package cn.wayuwa.wao.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
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

    @Test
    void exchangesCodeAndFallsBackToOpenIdFromWrappedUserResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FeishuLoginService loginService = new FeishuLoginService(
                new FeishuLoginProperties(true, "cli_test", "test-secret",
                        "https://wao.example/api/auth/feishu/callback", "https://wao.example",
                        "https://open.feishu.cn"), builder);
        String authorizationUrl = loginService.authorizationUrl("authorization");
        String state = URLDecoder.decode(
                URI.create(authorizationUrl).getQuery().replaceFirst(".*state=", ""), StandardCharsets.UTF_8);

        server.expect(requestTo("https://open.feishu.cn/open-apis/authen/v2/oauth/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"code\":0,\"access_token\":\"u-token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://open.feishu.cn/open-apis/authen/v1/user_info"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer u-token"))
                .andRespond(withSuccess("""
                        {"code":0,"msg":"success","data":{"open_id":"ou_test","name":"Tester","avatar_url":"avatar"}}
                        """, MediaType.APPLICATION_JSON));

        FeishuLoginService.FeishuProfile profile = loginService.exchange("auth-code", state);

        assertThat(profile.userId()).isEqualTo("ou_test");
        assertThat(profile.name()).isEqualTo("Tester");
        assertThat(profile.avatarUrl()).isEqualTo("avatar");
        server.verify();
    }

    @Test
    void reportsMissingConfigurationAsFeishuLoginFailure() {
        FeishuLoginService disabled = new FeishuLoginService(
                new FeishuLoginProperties(false, "", "", "https://wao.example/callback",
                        "https://wao.example", "https://open.feishu.cn"), RestClient.builder());

        assertThatThrownBy(() -> disabled.authorizationUrl("authorization"))
                .isInstanceOf(FeishuLoginException.class)
                .hasMessage("飞书登录尚未配置，请联系管理员");
    }
}
