package cn.wayuwa.wao.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class WechatOfficialAccountLoginServiceTest {
    private static final WechatOfficialAccountProperties PROPERTIES = new WechatOfficialAccountProperties(
            true, "wx-app", "app-secret", "callback-token", "https://api.weixin.qq.com",
            "https://mp.weixin.qq.com");

    @Test
    void createsParametricQrAndShortLivedVerificationCode() {
        FakeStore store = new FakeStore();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WechatOfficialAccountLoginService service = new WechatOfficialAccountLoginService(PROPERTIES, store, builder);
        server.expect(requestTo("https://api.weixin.qq.com/cgi-bin/stable_token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"grant_type":"client_credential","appid":"wx-app","secret":"app-secret",
                         "force_refresh":false}
                        """))
                .andRespond(withSuccess("{\"access_token\":\"official-token\",\"expires_in\":7200}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "https://api.weixin.qq.com/cgi-bin/qrcode/create?access_token=official-token")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"ticket\":\"ticket+/=\",\"expire_seconds\":300}",
                        MediaType.APPLICATION_JSON));

        WechatOfficialLoginStore.LoginSession session = service.createSession();

        assertThat(session.id()).matches("[0-9a-f]{32}");
        assertThat(session.verificationCode()).matches("\\d{4}");
        assertThat(session.qrCodeUrl()).isEqualTo(
                "https://mp.weixin.qq.com/cgi-bin/showqrcode?ticket=ticket%2B%2F%3D");
        assertThat(store.find(session.id())).contains(session);
        server.verify();
    }

    @Test
    void verifiesConfigurationThroughWechatStableTokenApi() {
        FakeStore store = new FakeStore();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WechatOfficialAccountLoginService service = new WechatOfficialAccountLoginService(PROPERTIES, store, builder);
        server.expect(requestTo("https://api.weixin.qq.com/cgi-bin/stable_token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"verified-token\",\"expires_in\":600}",
                        MediaType.APPLICATION_JSON));

        WechatOfficialAccountLoginService.Verification verification = service.verifyConfiguration();

        assertThat(verification.ready()).isTrue();
        assertThat(verification.message()).contains("验证通过");
        assertThat(store.accessToken()).contains("verified-token");
        server.verify();
    }

    @Test
    void reportsRejectedEgressIpFromWechatWithoutExposingCredentials() {
        FakeStore store = new FakeStore();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WechatOfficialAccountLoginService service = new WechatOfficialAccountLoginService(PROPERTIES, store, builder);
        server.expect(requestTo("https://api.weixin.qq.com/cgi-bin/stable_token"))
                .andRespond(withSuccess("""
                        {"errcode":40164,"errmsg":"invalid ip 171.83.123.164 ipv6 ::ffff:171.83.123.164, not in whitelist"}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(service::verifyConfiguration)
                .isInstanceOf(WechatLoginException.class)
                .hasMessage("微信拒绝服务器出口 IP 171.83.123.164，请将它加入公众号后台的 IP 白名单（微信错误码 40164）")
                .hasMessageNotContaining("app-secret");
        server.verify();
    }

    @Test
    void fallsBackToLegacyTokenEndpointWhenStableTransportIsUnavailable() {
        FakeStore store = new FakeStore();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WechatOfficialAccountLoginService service = new WechatOfficialAccountLoginService(PROPERTIES, store, builder);
        server.expect(requestTo("https://api.weixin.qq.com/cgi-bin/stable_token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo("https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=wx-app&secret=app-secret"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"errcode":40164,"errmsg":"invalid ip 171.83.123.164 ipv6 ::ffff:171.83.123.164, not in whitelist"}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(service::verifyConfiguration)
                .isInstanceOf(WechatLoginException.class)
                .hasMessageContaining("171.83.123.164")
                .hasMessageContaining("40164");
        server.verify();
    }

    @Test
    void distinguishesInvalidAppSecretFromIpWhitelistFailure() {
        FakeStore store = new FakeStore();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WechatOfficialAccountLoginService service = new WechatOfficialAccountLoginService(PROPERTIES, store, builder);
        server.expect(requestTo("https://api.weixin.qq.com/cgi-bin/stable_token"))
                .andRespond(withSuccess("{\"errcode\":40125,\"errmsg\":\"invalid appsecret\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(service::verifyConfiguration)
                .isInstanceOf(WechatLoginException.class)
                .hasMessageContaining("App Secret 无效")
                .hasMessageContaining("40125");
        server.verify();
    }

    @Test
    void correlatesScanAndTextCodeBeforeCompletingLogin() throws Exception {
        FakeStore store = new FakeStore();
        WechatOfficialAccountLoginService service = new WechatOfficialAccountLoginService(
                PROPERTIES, store, RestClient.builder());
        store.save(new WechatOfficialLoginStore.LoginSession("scene-1", "2503",
                WechatOfficialLoginStore.Status.WAITING_SCAN, null, Instant.now().plusSeconds(300), "qr"));

        String timestamp = "1788141345";
        String nonce = "nonce";
        assertThat(service.validSignature(signature(timestamp, nonce), timestamp, nonce)).isTrue();
        assertThat(service.validSignature("tampered", timestamp, nonce)).isFalse();

        String scanReply = service.handleMessage("""
                <xml><ToUserName><![CDATA[gh_wao]]></ToUserName><FromUserName><![CDATA[openid-1]]></FromUserName>
                <MsgType><![CDATA[event]]></MsgType><Event><![CDATA[subscribe]]></Event>
                <EventKey><![CDATA[qrscene_scene-1]]></EventKey></xml>
                """);
        assertThat(scanReply).contains("请回复页面上的 4 位验证码");
        assertThat(store.find("scene-1").orElseThrow().status())
                .isEqualTo(WechatOfficialLoginStore.Status.WAITING_CODE);

        String wrongCodeReply = service.handleMessage("""
                <xml><ToUserName><![CDATA[gh_wao]]></ToUserName><FromUserName><![CDATA[openid-1]]></FromUserName>
                <MsgType><![CDATA[text]]></MsgType><Content><![CDATA[0000]]></Content></xml>
                """);
        assertThat(wrongCodeReply).contains("验证码不正确");
        assertThat(store.find("scene-1").orElseThrow().status())
                .isEqualTo(WechatOfficialLoginStore.Status.WAITING_CODE);

        String codeReply = service.handleMessage("""
                <xml><ToUserName><![CDATA[gh_wao]]></ToUserName><FromUserName><![CDATA[openid-1]]></FromUserName>
                <MsgType><![CDATA[text]]></MsgType><Content><![CDATA[2503]]></Content></xml>
                """);
        assertThat(codeReply).contains("验证成功，请返回浏览器继续登录");
        assertThat(store.find("scene-1").orElseThrow().status())
                .isEqualTo(WechatOfficialLoginStore.Status.COMPLETED);
    }

    @Test
    void readsUnionIdentityFromOfficialFollowerProfile() {
        FakeStore store = new FakeStore();
        store.saveAccessToken("official-token", 7200);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WechatOfficialAccountLoginService service = new WechatOfficialAccountLoginService(PROPERTIES, store, builder);
        server.expect(requestTo("https://api.weixin.qq.com/cgi-bin/user/info?access_token=official-token&openid=openid-1&lang=zh_CN"))
                .andRespond(withSuccess("""
                        {"openid":"openid-1","unionid":"union-1","nickname":"微信成员","headimgurl":"avatar"}
                        """, MediaType.APPLICATION_JSON));

        WechatOfficialAccountLoginService.OfficialProfile profile = service.profile("openid-1");

        assertThat(profile.userId()).isEqualTo("official:wx-app:openid-1");
        assertThat(profile.unionId()).isEqualTo("union-1");
        assertThat(profile.name()).isEqualTo("微信成员");
        server.verify();
    }

    @Test
    void reportsConfigurationRequirementsWithoutFabricatingQr() {
        WechatOfficialAccountLoginService service = new WechatOfficialAccountLoginService(
                new WechatOfficialAccountProperties(false, "", "", "", "https://api.weixin.qq.com",
                        "https://mp.weixin.qq.com"), new FakeStore(), RestClient.builder());

        assertThatThrownBy(service::createSession).isInstanceOf(WechatLoginException.class)
                .hasMessageContaining("认证公众号");
    }

    private static String signature(String timestamp, String nonce) throws Exception {
        String source = Stream.of("callback-token", timestamp, nonce).sorted(Comparator.naturalOrder())
                .collect(Collectors.joining());
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                .digest(source.getBytes(StandardCharsets.UTF_8)));
    }

    private static final class FakeStore implements WechatOfficialLoginStore {
        private final Map<String, LoginSession> sessions = new HashMap<>();
        private final Map<String, String> scans = new HashMap<>();
        private String token;

        @Override public void save(LoginSession session) { sessions.put(session.id(), session); }
        @Override public Optional<LoginSession> find(String sessionId) {
            return Optional.ofNullable(sessions.get(sessionId)).filter(value -> value.expiresAt().isAfter(Instant.now()));
        }
        @Override public boolean markScanned(String scene, String openId) {
            LoginSession current = sessions.get(scene);
            if (current == null) return false;
            sessions.put(scene, new LoginSession(current.id(), current.verificationCode(), Status.WAITING_CODE,
                    openId, current.expiresAt(), current.qrCodeUrl()));
            scans.put(openId, scene);
            return true;
        }
        @Override public CodeResult complete(String openId, String verificationCode) {
            LoginSession current = sessions.get(scans.get(openId));
            if (current == null) return CodeResult.NO_ACTIVE_SCAN;
            if (!current.verificationCode().equals(verificationCode)) return CodeResult.INVALID;
            sessions.put(current.id(), new LoginSession(current.id(), current.verificationCode(), Status.COMPLETED,
                    openId, current.expiresAt(), current.qrCodeUrl()));
            return CodeResult.MATCHED;
        }
        @Override public Optional<String> accessToken() { return Optional.ofNullable(token); }
        @Override public void saveAccessToken(String accessToken, long expiresInSeconds) { token = accessToken; }
        @Override public void clearAccessToken() { token = null; }
    }
}
