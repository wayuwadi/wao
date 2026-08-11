package cn.wayuwa.wao.agent.platform.douyin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import cn.wayuwa.wao.agent.common.BadRequestException;
import cn.wayuwa.wao.agent.platform.MediaKind;
import cn.wayuwa.wao.agent.platform.PlatformCredential;
import cn.wayuwa.wao.agent.platform.PlatformIdentity;
import cn.wayuwa.wao.agent.platform.PublishRequest;
import cn.wayuwa.wao.agent.platform.PublishResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class DouyinPublishPlatformTest {
    private static final String BASE = "https://douyin.test";

    @TempDir Path tempDir;

    private MockRestServiceServer server;
    private DouyinPublishPlatform platform;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        platform = new DouyinPublishPlatform(configured(), builder);
    }

    private DouyinProperties configured() {
        return new DouyinProperties("ck-1", "cs-1",
                "https://wao.wayuwa.cn/api/agent/platforms/DOUYIN/callback",
                "user_info,video.create.bind", BASE);
    }

    @Test
    void withoutCredentialsCapabilityExplainsWhatIsMissing() {
        DouyinPublishPlatform blank = new DouyinPublishPlatform(
                new DouyinProperties("", "", "", "", BASE), RestClient.builder());

        assertThat(blank.capability().publishSupported()).isFalse();
        assertThat(blank.capability().note()).contains("video.create.bind");
        assertThatThrownBy(() -> blank.authorizeUrl("s")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void configuredCapabilityAdvertisesVideoPublishing() {
        assertThat(platform.capability().publishSupported()).isTrue();
        assertThat(platform.capability().mediaKinds()).containsExactly(MediaKind.VIDEO);
    }

    @Test
    void authorizeUrlCarriesClientKeyScopeAndEncodedRedirect() {
        String url = platform.authorizeUrl("state-abc");

        assertThat(url).startsWith(BASE + "/platform/oauth/connect/");
        assertThat(url).contains("client_key=ck-1");
        assertThat(url).contains("response_type=code");
        assertThat(url).contains("scope=user_info%2Cvideo.create.bind");
        assertThat(url).contains("redirect_uri=https%3A%2F%2Fwao.wayuwa.cn%2Fapi%2Fagent%2Fplatforms%2FDOUYIN%2Fcallback");
        assertThat(url).endsWith("state=state-abc");
    }

    @Test
    void exchangeCodeMapsTokensAndExpiry() {
        server.expect(requestTo(BASE + "/oauth/access_token/"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"data":{"error_code":0,"open_id":"open-abc123","access_token":"at-1",
                        "refresh_token":"rt-1","expires_in":86400,"refresh_expires_in":2592000,
                        "scope":"user_info,video.create.bind"}}
                        """, MediaType.APPLICATION_JSON));

        PlatformIdentity identity = platform.exchangeCode("code-1");

        server.verify();
        PlatformCredential credential = identity.credential();
        assertThat(credential.platformUserId()).isEqualTo("open-abc123");
        assertThat(credential.accessToken()).isEqualTo("at-1");
        assertThat(credential.refreshToken()).isEqualTo("rt-1");
        assertThat(credential.scope()).contains("video.create.bind");
        assertThat(credential.accessTokenExpiresAt()).isAfter(LocalDateTime.now().plusHours(23));
        assertThat(identity.displayName()).contains("abc123");
    }

    @Test
    void businessErrorInsideHttp200IsSurfaced() {
        server.expect(requestTo(BASE + "/oauth/access_token/"))
                .andRespond(withSuccess("""
                        {"data":{"error_code":2190008,"description":"code 已被使用"}}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> platform.exchangeCode("used-code"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("2190008")
                .hasMessageContaining("code 已被使用");
    }

    @Test
    void publishUploadsVideoThenCreatesItem() throws IOException {
        Path video = tempDir.resolve("clip.mp4");
        Files.write(video, new byte[] {1, 2, 3, 4});

        server.expect(requestTo(BASE + "/api/douyin/v1/video/upload_video/?open_id=open-abc123"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("access-token", "at-1"))
                .andRespond(withSuccess("""
                        {"data":{"error_code":0,"video":{"video_id":"vid-9"}}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/douyin/v1/video/create_video/?open_id=open-abc123"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("access-token", "at-1"))
                .andRespond(withSuccess("""
                        {"data":{"error_code":0,"item_id":"item-77"}}
                        """, MediaType.APPLICATION_JSON));

        PublishResult result = platform.publish(credential(), new PublishRequest("标题", "正文内容",
                List.of("露营", "户外"), MediaKind.VIDEO, video, "clip.mp4"));

        server.verify();
        assertThat(result.platformItemId()).isEqualTo("item-77");
    }

    @Test
    void publishRejectsNonVideoMediaBeforeCallingPlatform() {
        assertThatThrownBy(() -> platform.publish(credential(), new PublishRequest("标题", "正文",
                List.of(), MediaKind.IMAGE, tempDir.resolve("a.png"), "a.png")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("只接入了视频发布");
        server.verify();
    }

    @Test
    void publishRejectsMissingMediaFile() {
        assertThatThrownBy(() -> platform.publish(credential(), new PublishRequest("标题", "正文",
                List.of(), MediaKind.VIDEO, tempDir.resolve("missing.mp4"), "missing.mp4")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("不存在");
        server.verify();
    }

    @Test
    void captionAppendsHashTags() {
        PublishRequest request = new PublishRequest("标题", "今天聊露营",
                List.of("露营", " 户外 "), MediaKind.VIDEO, null, null);

        assertThat(request.captionWithTags()).isEqualTo("今天聊露营 #露营 #户外");
    }

    @Test
    void refreshRequiresRefreshToken() {
        PlatformCredential noRefresh = new PlatformCredential("open-abc123", "at-1", null, null,
                LocalDateTime.now(), null);

        assertThatThrownBy(() -> platform.refresh(noRefresh))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("重新授权");
    }

    private PlatformCredential credential() {
        return new PlatformCredential("open-abc123", "at-1", "rt-1", "video.create.bind",
                LocalDateTime.now().plusHours(2), LocalDateTime.now().plusDays(20));
    }
}
