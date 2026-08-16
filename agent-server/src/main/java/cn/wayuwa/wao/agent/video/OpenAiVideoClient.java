package cn.wayuwa.wao.agent.video;

import cn.wayuwa.wao.agent.common.BadRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.OutputStream;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** OpenAI Videos API 的窄接口适配器，不把临时下载地址或凭据暴露给业务层。 */
@Component
public class OpenAiVideoClient {
    private final VideoGenerationProperties properties;
    private final RestClient restClient;

    public OpenAiVideoClient(VideoGenerationProperties properties,
                             @Qualifier("videoRestClient") RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    public boolean available() {
        return properties.available();
    }

    /** 创建真实上游任务；幂等键由本地任务与尝试次数组成。 */
    public RemoteVideo create(String prompt, String model, int seconds, String size, String idempotencyKey) {
        MultiValueMap<String, Object> payload = new LinkedMultiValueMap<>();
        payload.add("prompt", prompt);
        payload.add("model", model);
        payload.add("seconds", String.valueOf(seconds));
        payload.add("size", size);
        try {
            JsonNode json = restClient.post().uri(properties.base() + "/videos")
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(payload)
                    .retrieve()
                    .onStatus(status -> status.isError(), (request, response) -> {
                        throw upstreamError("创建", response.getStatusCode().value(), response.getBody().readNBytes(4096));
                    })
                    .body(JsonNode.class);
            return parse(json, "创建");
        } catch (RestClientException ex) {
            throw new BadRequestException("调用 OpenAI 视频创建接口失败：" + safeMessage(ex));
        }
    }

    /** 查询真实上游任务状态。 */
    public RemoteVideo retrieve(String providerJobId) {
        try {
            JsonNode json = restClient.get().uri(properties.base() + "/videos/{id}", providerJobId)
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .retrieve()
                    .onStatus(status -> status.isError(), (request, response) -> {
                        throw upstreamError("查询", response.getStatusCode().value(), response.getBody().readNBytes(4096));
                    })
                    .body(JsonNode.class);
            return parse(json, "查询");
        } catch (RestClientException ex) {
            throw new BadRequestException("调用 OpenAI 视频查询接口失败：" + safeMessage(ex));
        }
    }

    /** 将完成视频流式写入目标输出，超过媒体卷上限时立即中止。 */
    public void download(String providerJobId, OutputStream output, long maxBytes) {
        try {
            restClient.get().uri(properties.base() + "/videos/{id}/content", providerJobId)
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .accept(MediaType.valueOf("video/mp4"))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            throw upstreamError("下载", response.getStatusCode().value(), response.getBody().readNBytes(4096));
                        }
                        byte[] buffer = new byte[64 * 1024];
                        long total = 0;
                        int read;
                        while ((read = response.getBody().read(buffer)) >= 0) {
                            total += read;
                            if (total > maxBytes) throw new BadRequestException("生成视频超过媒体文件大小上限");
                            output.write(buffer, 0, read);
                        }
                        return null;
                    });
        } catch (RestClientException ex) {
            throw new BadRequestException("下载 OpenAI 生成视频失败：" + safeMessage(ex));
        }
    }

    private RemoteVideo parse(JsonNode json, String action) {
        if (json == null || !StringUtils.hasText(json.path("id").asText(null))
                || !StringUtils.hasText(json.path("status").asText(null))) {
            throw new BadRequestException("OpenAI 视频" + action + "接口返回缺少任务标识或状态");
        }
        String error = json.path("error").path("message").asText(null);
        return new RemoteVideo(json.path("id").asText(), json.path("status").asText(),
                Math.max(0, Math.min(100, json.path("progress").asInt(0))), error);
    }

    private BadRequestException upstreamError(String action, int status, byte[] body) {
        String message = null;
        try {
            JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            message = json.path("error").path("message").asText(null);
        } catch (IOException ignored) {
            // 非 JSON 错误只展示状态码，避免把代理页面或敏感响应原文带到前端。
        }
        return new BadRequestException("OpenAI 视频" + action + "失败（HTTP " + status + "）"
                + (StringUtils.hasText(message) ? "：" + message : ""));
    }

    private String safeMessage(RuntimeException ex) {
        return StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ex.getClass().getSimpleName();
    }

    public record RemoteVideo(String id, String status, int progress, String error) { }
}
