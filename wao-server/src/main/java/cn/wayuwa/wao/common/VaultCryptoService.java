package cn.wayuwa.wao.common;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.ProviderException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 密钥库条目密码的 AES-256-GCM 加解密。密钥来自 {@code wao.vault.encryption-key}（环境变量
 * {@code WAO_VAULT_KEY}，base64 编码的 32 字节），本进程内不落库、不出现在日志中。
 *
 * <p>这是「服务端静态加密」而非零知识端到端加密：拥有数据库与本密钥的一方仍可解密。真正的端到端
 * （客户端主密码派生、服务端不可解密）依赖尚不存在的用户级鉴权体系，留待后续单独立项。
 */
@Component
public class VaultCryptoService {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int NONCE_BYTES = 12;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public VaultCryptoService(@Value("${wao.vault.encryption-key}") String base64Key) {
        byte[] raw = Base64.getDecoder().decode(base64Key);
        if (raw.length != 32) {
            throw new IllegalStateException("wao.vault.encryption-key 必须是 base64 编码的 32 字节 AES-256 密钥");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    /** 加密明文，返回密文与本次使用的随机 nonce。 */
    public Encrypted encrypt(String plaintext) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new Encrypted(ciphertext, nonce);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("密码加密失败", ex);
        }
    }

    /** 用给定 nonce 解密密文。 */
    public String decrypt(byte[] ciphertext, byte[] nonce) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | ProviderException ex) {
            throw new IllegalStateException("密码解密失败", ex);
        }
    }

    /** 加密结果：密文 + 本次随机 nonce。 */
    public record Encrypted(byte[] ciphertext, byte[] nonce) {
    }
}
