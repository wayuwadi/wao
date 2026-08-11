package cn.wayuwa.wao.agent.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.wayuwa.wao.agent.common.BadRequestException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class CredentialCipherTest {
    private static final String KEY = "d2FvLWFnZW50LWxvY2FsLWRldi1vbmx5LWtleS0zMmI=";

    @Test
    void roundTripsCredentialWithoutLosingFields() {
        CredentialCipher cipher = new CredentialCipher(KEY, new MockEnvironment());
        LocalDateTime expiry = LocalDateTime.of(2026, 8, 1, 10, 30);
        PlatformCredential original = new PlatformCredential("open-id-1", "access-1", "refresh-1",
                "user_info,video.create.bind", expiry, expiry.plusDays(30));

        PlatformCredential restored = cipher.decrypt(cipher.encrypt(original));

        assertThat(restored).isEqualTo(original);
    }

    @Test
    void producesDifferentCipherTextForSamePlaintext() {
        CredentialCipher cipher = new CredentialCipher(KEY, new MockEnvironment());
        PlatformCredential credential = new PlatformCredential("open-id-1", "access-1", "refresh-1",
                "user_info", LocalDateTime.now(), LocalDateTime.now().plusDays(30));

        assertThat(cipher.encrypt(credential)).isNotEqualTo(cipher.encrypt(credential));
    }

    /**
     * 缺密钥不能拖垮整个应用 —— 一次真实部署就栽在这里：节点 .env 没有这个变量，
     * 容器起不来触发回滚。未配置属于「功能不可用」，与抖音缺凭据同类，不是启动错误。
     */
    @Test
    void prodStartsWithoutKeyButRefusesToHandleCredentials() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");

        CredentialCipher cipher = new CredentialCipher("", prod);

        assertThat(cipher.ready()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt(new PlatformCredential("o", "t", null, null, null, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("AGENT_CREDENTIAL_KEY");
        assertThatThrownBy(() -> cipher.decrypt("whatever"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("AGENT_CREDENTIAL_KEY");
    }

    /** 写了但写错是配置错误，必须立刻暴露，不能静默降级成「未配置」。 */
    @Test
    void rejectsKeyOfWrongLength() {
        assertThatThrownBy(() -> new CredentialCipher("c2hvcnQta2V5", new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 字节");
    }

    @Test
    void rejectsKeyThatIsNotBase64() {
        assertThatThrownBy(() -> new CredentialCipher("这不是 base64!!", new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Base64");
    }

    @Test
    void configuredKeyIsReady() {
        assertThat(new CredentialCipher(KEY, new MockEnvironment()).ready()).isTrue();
    }

    @Test
    void localProfileFallsBackToDevKey() {
        CredentialCipher cipher = new CredentialCipher(null, new MockEnvironment());
        PlatformCredential credential = new PlatformCredential("open-id", "token", null, null, null, null);

        assertThat(cipher.decrypt(cipher.encrypt(credential)).accessToken()).isEqualTo("token");
    }
}
