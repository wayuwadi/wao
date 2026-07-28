package cn.wayuwa.wao.service;

import java.net.URI;
import java.util.List;

import cn.wayuwa.wao.common.BadRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Responses 兼容自定义 API 的地址校验与连通性探测。 */
@Component
public class AiCustomApiClient {

    private final RestClient restClient;

    public AiCustomApiClient(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    /** 规范化为以 {@code /v1} 结尾的 API 根地址。 */
    public String normalizeBaseUrl(String raw) {
        String value = raw == null ? "" : raw.trim().replaceAll("/+$", "");
        if (!StringUtils.hasText(value)) throw new BadRequestException("请填写自定义 API Base URL");
        URI uri;
        try {
            uri = URI.create(value);
        } catch (RuntimeException ex) {
            throw new BadRequestException("自定义 API Base URL 格式不正确");
        }
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || !StringUtils.hasText(uri.getHost())) {
            throw new BadRequestException("自定义 API Base URL 必须是 HTTP 或 HTTPS 地址");
        }
        return value.endsWith("/v1") ? value : value + "/v1";
    }

    /** 调用真实 {@code /v1/models} 验证网络和鉴权，并返回可用模型 id。 */
    public List<String> models(String baseUrl, String apiKey) {
        JsonNode json;
        try {
            json = restClient.get().uri(normalizeBaseUrl(baseUrl) + "/models")
                    .header("Authorization", "Bearer " + apiKey)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException ex) {
            throw new BadRequestException("连接自定义 API 失败：" + ex.getMessage());
        }
        if (json == null || !json.path("data").isArray()) {
            throw new BadRequestException("自定义 API 的 /models 响应格式不兼容 OpenAI 协议");
        }
        return json.path("data").findValuesAsText("id").stream()
                .filter(StringUtils::hasText).distinct().sorted().toList();
    }
}
