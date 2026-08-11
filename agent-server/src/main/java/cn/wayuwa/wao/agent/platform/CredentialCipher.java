package cn.wayuwa.wao.agent.platform;

import cn.wayuwa.wao.agent.common.BadRequestException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 平台令牌的对称加解密。密钥来自 {@code AGENT_CREDENTIAL_KEY}（Base64 编码的 32 字节）。
 *
 * <p>prod 未配置密钥时不阻止启动，但 {@link #ready()} 为 false，账号接管会被声明为不可用 ——
 * 与抖音缺凭据、模型缺 Key 的处理保持一致。加解密调用一律拒绝，因此不可能出现明文令牌落库。
 * 密钥写了但写错（非法 Base64 或长度不对）仍然启动失败，因为那是配置错误而不是未配置。
 * 本地开发回退到固定测试密钥。
 */
@Component
public class CredentialCipher {
    private static final Logger log = LoggerFactory.getLogger(CredentialCipher.class);
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    /** 仅供本地 H2 使用，生产由环境变量覆盖。 */
    private static final String LOCAL_DEV_KEY = "d2FvLWFnZW50LWxvY2FsLWRldi1vbmx5LWtleS0zMmI=";

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public CredentialCipher(@Value("${wao-agent.credential-key:}") String configuredKey, Environment environment) {
        String material = configuredKey;
        if (!StringUtils.hasText(material)) {
            if (environment.matchesProfiles("prod")) {
                // 未配置不阻止启动：Agent 的其他功能与平台接管无关。但账号接管会被声明为不可用。
                log.warn("未配置 AGENT_CREDENTIAL_KEY，平台账号接管功能不可用；配置后重启即可启用");
                this.key = null;
                return;
            }
            material = LOCAL_DEV_KEY;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(material.trim());
        } catch (IllegalArgumentException ex) {
            // 配错和没配是两回事：写了但写错必须立刻暴露，否则会静默降级。
            throw new IllegalStateException("AGENT_CREDENTIAL_KEY 不是合法的 Base64，请用 openssl rand -base64 32 生成", ex);
        }
        if (decoded.length != 32) {
            throw new IllegalStateException("AGENT_CREDENTIAL_KEY 必须是 Base64 编码的 32 字节密钥，当前为 "
                    + decoded.length + " 字节");
        }
        this.key = new SecretKeySpec(decoded, "AES");
    }

    /** 密钥是否就绪。未就绪时平台账号接管必须声明为不可用，而不是让用户点了才报错。 */
    public boolean ready() {
        return key != null;
    }

    /** 未配置密钥时的说明，直接展示给用户。 */
    public static String missingKeyNote() {
        return "尚未配置 AGENT_CREDENTIAL_KEY（用 openssl rand -base64 32 生成，两台节点必须相同），"
                + "平台令牌无法加密存储，账号接管暂不可用。";
    }

    /** 把凭据序列化并加密为可落库的字符串。 */
    public String encrypt(PlatformCredential credential) {
        requireKey();
        try {
            byte[] plain = mapper.writeValueAsBytes(credential);
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain);
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("平台凭据序列化失败", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("平台凭据加密失败", ex);
        }
    }

    /** 解密落库的凭据。 */
    public PlatformCredential decrypt(String cipherText) {
        requireKey();
        try {
            byte[] combined = Base64.getDecoder().decode(cipherText);
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(combined, IV_BYTES, combined.length - IV_BYTES);
            return mapper.readValue(new String(plain, StandardCharsets.UTF_8), PlatformCredential.class);
        } catch (Exception ex) {
            throw new IllegalStateException("平台凭据解密失败，可能是 AGENT_CREDENTIAL_KEY 变更导致", ex);
        }
    }

    private void requireKey() {
        if (key == null) throw new BadRequestException(missingKeyNote());
    }
}
