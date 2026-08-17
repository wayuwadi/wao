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

        assertThat(authorizationUrl).contains("passport.feishu.cn/suite/passport/oauth/authorize")
                .contains("client_id=cli_test");
        assertThat(service.callbackUri("auth-code", state).toString())
                .isEqualTo("https://wao.example/auth/feishu/callback?code=auth-code&state=" + state);
    }

    @Test
    void returnsWindowsLoginToSignedLoopbackCallback() {
        String callback = "http://127.0.0.1:49152/wao-auth/callback";
        String authorizationUrl = service.authorizationUrl("windows", callback);
        String state = URLDecoder.decode(
                URI.create(authorizationUrl).getQuery().replaceFirst(".*state=", ""), StandardCharsets.UTF_8);

        assertThat(authorizationUrl).contains("passport.feishu.cn/suite/passport/oauth/authorize");
        assertThat(service.callbackUri("windows-code", state).toString())
                .isEqualTo(callback + "?code=windows-code&state=" + state);
    }

    @Test
    void rejectsUnsafeWindowsCallbackBeforeCreatingAuthorizationUrl() {
        assertThatThrownBy(() -> service.authorizationUrl("windows", "https://evil.example/wao-auth/callback"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid Windows callback");
        assertThatThrownBy(() -> service.authorizationUrl("windows", "http://127.0.0.1:49152/other"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid Windows callback");
        assertThatThrownBy(() -> service.authorizationUrl("qr", "http://127.0.0.1:49152/wao-auth/callback"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Callback is only supported for Windows login");
    }

    @Test
    void exchangesQrCodeThroughLegacyFlowRequiredByQrSdk() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FeishuLoginService loginService = new FeishuLoginService(
                new FeishuLoginProperties(true, "cli_test", "test-secret",
                        "https://wao.example/api/auth/feishu/callback", "https://wao.example",
                        "https://open.feishu.cn"), builder);
        String authorizationUrl = loginService.authorizationUrl("qr");
        String state = URLDecoder.decode(
                URI.create(authorizationUrl).getQuery().replaceFirst(".*state=", ""), StandardCharsets.UTF_8);

        server.expect(requestTo("https://open.feishu.cn/open-apis/auth/v3/app_access_token/internal"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"code\":0,\"app_access_token\":\"a-token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://open.feishu.cn/open-apis/authen/v1/access_token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer a-token"))
                .andRespond(withSuccess("{\"code\":0,\"data\":{\"access_token\":\"u-token\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://open.feishu.cn/open-apis/authen/v1/user_info"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer u-token"))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"open_id":"ou_qr","name":"QR Tester"}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(loginService.exchange("qr-code", state).userId()).isEqualTo("ou_qr");
        server.verify();
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
    void exchangesInAppCodeWithoutOAuthStateForFeishuContainerLogin() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FeishuLoginService loginService = new FeishuLoginService(
                new FeishuLoginProperties(true, "cli_test", "test-secret",
                        "https://wao.example/api/auth/feishu/callback", "https://wao.example",
                        "https://open.feishu.cn"), builder);

        server.expect(requestTo("https://open.feishu.cn/open-apis/auth/v3/app_access_token/internal"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"code\":0,\"app_access_token\":\"a-token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://open.feishu.cn/open-apis/authen/v1/access_token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer a-token"))
                .andRespond(withSuccess("{\"code\":0,\"data\":{\"access_token\":\"u-token\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://open.feishu.cn/open-apis/authen/v1/user_info"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer u-token"))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"open_id":"ou_in_app","name":"容器内用户"}}
                        """, MediaType.APPLICATION_JSON));

        FeishuLoginService.FeishuProfile profile = loginService.exchangeInApp("jssdk-code");

        assertThat(profile.userId()).isEqualTo("ou_in_app");
        assertThat(profile.scene()).isEqualTo("in_app");
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
        assertThatThrownBy(() -> disabled.exchangeInApp("jssdk-code"))
                .isInstanceOf(FeishuLoginException.class)
                .hasMessage("飞书登录尚未配置，请联系管理员");
        assertThat(disabled.configured()).isFalse();
    }
}
