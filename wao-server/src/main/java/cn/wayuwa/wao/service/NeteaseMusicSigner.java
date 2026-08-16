package cn.wayuwa.wao.service;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import cn.wayuwa.wao.config.NeteaseMusicProperties;

/** 按网易云开放平台规范对非空参数排序并执行 SHA256withRSA 签名。 */
@Component
public class NeteaseMusicSigner {
    private final NeteaseMusicProperties properties;

    public NeteaseMusicSigner(NeteaseMusicProperties properties) {
        this.properties = properties;
    }

    /** 返回 Base64 签名；私钥解析和明文请求均不会写日志。 */
    public String sign(Map<String, String> parameters) {
        try {
            String content = parameters.entrySet().stream()
                    .filter(entry -> !"sign".equals(entry.getKey()))
                    .filter(entry -> entry.getValue() != null && !entry.getValue().isEmpty())
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(Collectors.joining("&"));
            String encoded = properties.privateKey()
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            PrivateKey key = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)));
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(key);
            signature.update(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception ex) {
            throw new IllegalStateException("网易云开放平台 RSA 私钥无效", ex);
        }
    }
}
