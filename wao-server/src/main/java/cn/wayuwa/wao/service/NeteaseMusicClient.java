package cn.wayuwa.wao.service;

import java.util.Map;
import java.util.TreeMap;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.config.NeteaseMusicProperties;

/** 网易云音乐开放平台客户端：统一补齐设备参数、RSA 签名并解析业务错误。 */
@Component
public class NeteaseMusicClient {
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final NeteaseMusicProperties properties;
    private final NeteaseMusicSigner signer;

    public NeteaseMusicClient(RestClient.Builder builder, ObjectMapper objectMapper,
            NeteaseMusicProperties properties, NeteaseMusicSigner signer) {
        this.restClient = builder.build();
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.signer = signer;
    }

    /** 配置是否足以发起正式签名请求。 */
    public boolean configured() {
        return properties.enabled() && has(properties.appId()) && has(properties.appSecret())
                && has(properties.privateKey()) && has(properties.redirectUri()) && has(properties.apiBaseUrl())
                && has(properties.authorizeUrl()) && has(properties.channel()) && has(properties.deviceType())
                && has(properties.clientType()) && has(properties.os()) && has(properties.osVer())
                && has(properties.appVer()) && has(properties.brand()) && has(properties.model());
    }

    /** 调用一个官方 OpenAPI，返回完整响应以保留 subCode。 */
    public JsonNode call(String path, String accessToken, String deviceId, String clientIp, String netStatus,
            Map<String, ?> businessParameters) {
        ensureConfigured();
        try {
            Map<String, String> parameters = new TreeMap<>();
            parameters.put("appId", properties.appId());
            parameters.put("appSecret", properties.appSecret());
            parameters.put("signType", "RSA_SHA256");
            parameters.put("timestamp", String.valueOf(System.currentTimeMillis()));
            parameters.put("bizContent", objectMapper.writeValueAsString(businessParameters));
            parameters.put("device", objectMapper.writeValueAsString(Map.ofEntries(
                    Map.entry("channel", properties.channel()),
                    Map.entry("deviceId", deviceId),
                    Map.entry("deviceType", properties.deviceType()),
                    Map.entry("appVer", properties.appVer()),
                    Map.entry("os", properties.os()),
                    Map.entry("osVer", properties.osVer()),
                    Map.entry("brand", properties.brand()),
                    Map.entry("model", properties.model()),
                    Map.entry("clientIp", valueOr(clientIp, "127.0.0.1")),
                    Map.entry("netStatus", netStatus),
                    Map.entry("flowFlag", "init"))));
            if (StringUtils.hasText(accessToken)) parameters.put("accessToken", accessToken);
            parameters.put("sign", signer.sign(parameters));

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            parameters.forEach(form::add);
            String body = restClient.post()
                    .uri(trimSlash(properties.apiBaseUrl()) + path)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(body);
            int code = root.path("code").asInt(500);
            if (code != 200) throw new NeteaseMusicApiException(code, root.path("message").asText());
            return root;
        } catch (NeteaseMusicApiException ex) {
            throw ex;
        } catch (RestClientException | JsonProcessingException ex) {
            throw new NeteaseMusicApiException(503, "网易云音乐服务连接失败，请稍后重试");
        }
    }

    public NeteaseMusicProperties properties() { return properties; }

    private void ensureConfigured() {
        if (!configured()) throw new BadRequestException("管理员尚未完成网易云音乐开放平台配置");
    }

    private static boolean has(String value) { return StringUtils.hasText(value); }
    private static String valueOr(String value, String fallback) { return has(value) ? value : fallback; }
    private static String trimSlash(String value) { return value.endsWith("/") ? value.substring(0, value.length() - 1) : value; }
}
