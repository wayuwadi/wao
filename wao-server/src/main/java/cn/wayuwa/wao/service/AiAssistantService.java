package cn.wayuwa.wao.service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import cn.wayuwa.wao.dto.AssistantChatRequest;
import cn.wayuwa.wao.dto.AssistantChatRequest.ChatMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 助手对话服务。
 *
 * <p>调用 OpenAI 兼容的 chat completions API（支持 OpenAI / Claude / DeepSeek / 通义等）。
 * 配置缺失时返回友好的降级提示，避免阻塞前端开发。
 */
@Service
public class AiAssistantService {

    private static final String SYSTEM_PROMPT = """
            你是 WAO Life OS 的智能助手。你可以帮用户：
            - 总结当前页面的内容
            - 创建新任务或新项目（请给出明确的标题和描述建议）
            - 解释错误信息和排查思路
            - 回答关于 WAO 系统使用方法的问题
            
            请用简洁、友好的中文回复。如果用户提供了页面上下文，优先基于该上下文回答。
            """;

    private final RestClient restClient;
    private final ObjectMapper json;
    private final String apiKey;
    private final String baseUrl;
    private final String model;

    public AiAssistantService(RestClient.Builder builder, ObjectMapper json,
            @Value("${wao.assistant.api-key:}") String apiKey,
            @Value("${wao.assistant.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${wao.assistant.model:gpt-4o-mini}") String model) {
        this.restClient = builder.build();
        this.json = json;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
    }

    /** 是否已配置 API key。 */
    public boolean configured() {
        return StringUtils.hasText(apiKey);
    }

    /**
     * 流式返回助手回复。通过 SSE 把每个 chunk 推送给前端，降低首字等待时间。
     */
    public void chatStream(AssistantChatRequest request, SseEmitter emitter) {
        if (!configured()) {
            sendFallback(emitter);
            return;
        }
        try {
            ObjectNode body = buildRequestBody(request, true);
            ResponseEntity<String> response = restClient.post()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .retrieve()
                    .toEntity(String.class);
            String raw = response.getBody();
            if (raw == null) {
                sendFallback(emitter);
            } else {
                parseSseChunks(raw, emitter);
            }
        } catch (Exception ex) {
            sendError(emitter, "调用 AI 接口失败：" + ex.getMessage());
        } finally {
            emitter.complete();
        }
    }

    /** 一次性返回（不流式）；前端降级时使用。 */
    public String chatOnce(AssistantChatRequest request) {
        if (!configured()) {
            return "请先在配置文件 `application.yml` 中设置 `wao.assistant.api-key`，AI 助手才能正常工作。";
        }
        try {
            ObjectNode body = buildRequestBody(request, false);
            String raw = restClient.post()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
            if (raw == null) return "AI 接口没有返回内容";
            JsonNode node = json.readTree(raw);
            JsonNode choices = node.path("choices");
            if (!choices.isArray() || choices.isEmpty()) return "AI 接口返回格式异常";
            return choices.get(0).path("message").path("content").asText("").trim();
        } catch (Exception ex) {
            return "调用 AI 接口失败：" + ex.getMessage();
        }
    }

    private ObjectNode buildRequestBody(AssistantChatRequest request, boolean stream) {
        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        body.put("stream", stream);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", SYSTEM_PROMPT);
        if (StringUtils.hasText(request.pageContext())) {
            messages.addObject().put("role", "system")
                    .put("content", "当前页面上下文：\n" + request.pageContext());
        }
        if (request.history() != null) {
            for (ChatMessage m : request.history()) {
                messages.addObject().put("role", m.role()).put("content", m.content());
            }
        }
        messages.addObject().put("role", "user").put("content", request.message());
        return body;
    }

    private void parseSseChunks(String raw, SseEmitter emitter) {
        // SSE 格式：每个 chunk 以 "data: " 开头，"data: [DONE]" 表示结束
        StringBuilder buffer = new StringBuilder();
        for (String line : raw.split("\n")) {
            if (!line.startsWith("data: ")) continue;
            String payload = line.substring(6).trim();
            if ("[DONE]".equals(payload)) break;
            try {
                JsonNode node = json.readTree(payload);
                JsonNode delta = node.path("choices").path(0).path("delta");
                String content = delta.path("content").asText(null);
                if (content != null && !content.isEmpty()) {
                    buffer.append(content);
                    emitter.send(SseEmitter.event().name("token").data(content, MediaType.TEXT_PLAIN));
                }
            } catch (Exception ignore) {
                // 单个 chunk 解析失败不影响后续
            }
        }
        try {
            emitter.send(SseEmitter.event().name("done").data(buffer.toString()));
        } catch (Exception ignore) { /* ignore */ }
    }

    private void sendFallback(SseEmitter emitter) {
        String msg = "AI 助手尚未配置。请在 `application.yml` 中设置：\n\n"
                + "```yaml\nwao:\n  assistant:\n    api-key: your-api-key\n"
                + "    base-url: https://api.openai.com/v1  # 或其他兼容 API\n"
                + "    model: gpt-4o-mini\n```\n\n"
                + "配置后重启服务即可使用。";
        try {
            emitter.send(SseEmitter.event().name("token").data(msg, MediaType.TEXT_PLAIN));
            emitter.send(SseEmitter.event().name("done").data(msg));
        } catch (Exception ignore) { /* ignore */ }
    }

    private void sendError(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name("error").data(message, MediaType.TEXT_PLAIN));
        } catch (Exception ignore) { /* ignore */ }
    }
}
