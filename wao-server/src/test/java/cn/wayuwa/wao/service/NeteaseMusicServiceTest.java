package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import cn.wayuwa.wao.common.VaultCryptoService;
import cn.wayuwa.wao.config.NeteaseMusicProperties;
import cn.wayuwa.wao.domain.NeteaseMusicBindingEntity;
import cn.wayuwa.wao.domain.NeteaseMusicOauthStateEntity;
import cn.wayuwa.wao.mapper.NeteaseMusicBindingMapper;
import cn.wayuwa.wao.mapper.NeteaseMusicOauthStateMapper;

class NeteaseMusicServiceTest {
    private static final String TEST_KEY = "ZKp9ywcbuZOrQCrxx2kd+0N9VJfnzRNSQQZXMP/vXVw=";
    private static final String OAUTH_STATE = "123456789012345678901234567890";
    private final NeteaseMusicBindingMapper bindingMapper = mock(NeteaseMusicBindingMapper.class);
    private final NeteaseMusicOauthStateMapper stateMapper = mock(NeteaseMusicOauthStateMapper.class);
    private final NeteaseMusicClient client = mock(NeteaseMusicClient.class);
    private final VaultCryptoService crypto = new VaultCryptoService(TEST_KEY);
    private final NeteaseMusicService service = new NeteaseMusicService(bindingMapper, stateMapper, client, crypto);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void statusOnlyReadsCurrentMembersBindingAndNeverReturnsTokens() {
        when(client.configured()).thenReturn(true);
        NeteaseMusicBindingEntity binding = new NeteaseMusicBindingEntity();
        binding.setMemberId("member-a");
        binding.setNickname("成员 A 的网易云");
        binding.setStatus("CONNECTED");
        binding.setTokenExpiresAt(LocalDateTime.now().plusDays(2));
        when(bindingMapper.selectById("member-a")).thenReturn(binding);

        var result = service.status("member-a");

        verify(bindingMapper).selectById("member-a");
        assertThat(result.connected()).isTrue();
        assertThat(result.nickname()).isEqualTo("成员 A 的网易云");
        assertThat(result.toString()).doesNotContain("token");
    }

    @Test
    void authorizeBindsOneTimeStateToCurrentMemberAndKeepsStableDeviceId() {
        when(client.configured()).thenReturn(true);
        when(client.properties()).thenReturn(properties());
        NeteaseMusicBindingEntity existing = new NeteaseMusicBindingEntity();
        existing.setMemberId("member-a");
        existing.setDeviceId("waostabledevice123");
        when(bindingMapper.selectById("member-a")).thenReturn(existing);

        var result = service.authorize("member-a", "5g");

        ArgumentCaptor<NeteaseMusicOauthStateEntity> captor = ArgumentCaptor.forClass(NeteaseMusicOauthStateEntity.class);
        verify(stateMapper).insert(captor.capture());
        assertThat(captor.getValue().getMemberId()).isEqualTo("member-a");
        assertThat(captor.getValue().getDeviceId()).isEqualTo("waostabledevice123");
        assertThat(captor.getValue().getNetStatus()).isEqualTo("5g");
        assertThat(result.authorizeUrl()).contains("clientId=app", "clientType=web", "state=");
        assertThat(result.authorizeUrl()).doesNotContain("secret");
    }

    @Test
    void callbackEncryptsTokensIntoTheMemberSelectedByOauthState() throws Exception {
        NeteaseMusicOauthStateEntity state = new NeteaseMusicOauthStateEntity();
        state.setState(OAUTH_STATE);
        state.setMemberId("member-a");
        state.setDeviceId("waodevice123");
        state.setNetStatus("4g");
        state.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        when(stateMapper.selectById(OAUTH_STATE)).thenReturn(state);
        when(client.call(eq("/openapi/music/basic/user/oauth2/token/get/v2"), eq(null),
                eq("waodevice123"), eq("203.0.113.5"), eq("4g"), any()))
                .thenReturn(objectMapper.readTree("""
                        {"data":{"accessToken":"access-secret","refreshToken":"refresh-secret","expireIn":604800}}
                        """));
        when(client.call(eq("/openapi/music/basic/user/profile/get/v2"), eq("access-secret"),
                eq("waodevice123"), eq("203.0.113.5"), eq("4g"), any()))
                .thenReturn(objectMapper.readTree("""
                        {"data":{"id":"netease-a","nickname":"云村 A","avatarUrl":"https://example/avatar.png"}}
                        """));
        when(client.properties()).thenReturn(properties());

        service.callback("grant-code", OAUTH_STATE, "203.0.113.5");

        ArgumentCaptor<NeteaseMusicBindingEntity> captor = ArgumentCaptor.forClass(NeteaseMusicBindingEntity.class);
        verify(bindingMapper).insert(captor.capture());
        NeteaseMusicBindingEntity stored = captor.getValue();
        assertThat(stored.getMemberId()).isEqualTo("member-a");
        assertThat(stored.getAccessTokenCiphertext()).isNotEqualTo("access-secret".getBytes());
        assertThat(crypto.decrypt(stored.getAccessTokenCiphertext(), stored.getAccessTokenNonce()))
                .isEqualTo("access-secret");
        assertThat(crypto.decrypt(stored.getRefreshTokenCiphertext(), stored.getRefreshTokenNonce()))
                .isEqualTo("refresh-secret");
        verify(stateMapper).deleteById(OAUTH_STATE);
    }

    private static NeteaseMusicProperties properties() {
        return new NeteaseMusicProperties(true, "app", "secret", "private",
                "https://wao.example/api/music/netease/callback", "https://wao.example/",
                "https://openapi.music.163.com", "https://music.163.com/st/platform/oauth/authorize",
                "web", "wao", "web", "web", "1", "1.0.0", "browser", "browser");
    }
}
