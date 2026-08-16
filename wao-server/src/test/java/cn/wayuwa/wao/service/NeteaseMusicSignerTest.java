package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import cn.wayuwa.wao.config.NeteaseMusicProperties;

class NeteaseMusicSignerTest {

    @Test
    void signsNonEmptyParametersInAsciiKeyOrderWithPkcs8Key() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        String privateKey = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
        NeteaseMusicSigner signer = new NeteaseMusicSigner(properties(privateKey));
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("timestamp", "123");
        parameters.put("appId", "app");
        parameters.put("empty", "");
        parameters.put("bizContent", "{\"id\":1}");

        byte[] signatureBytes = Base64.getDecoder().decode(signer.sign(parameters));

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(pair.getPublic());
        verifier.update("appId=app&bizContent={\"id\":1}&timestamp=123".getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(signatureBytes)).isTrue();
    }

    private static NeteaseMusicProperties properties(String privateKey) {
        return new NeteaseMusicProperties(true, "app", "secret", privateKey,
                "https://wao.example/api/music/netease/callback", "https://wao.example/",
                "https://openapi.music.163.com", "https://music.163.com/st/platform/oauth/authorize",
                "web", "wao", "web", "web", "1", "1.0.0", "browser", "browser");
    }
}
