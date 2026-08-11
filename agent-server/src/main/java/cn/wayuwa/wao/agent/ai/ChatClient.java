package cn.wayuwa.wao.agent.ai;

import cn.wayuwa.wao.agent.common.BadRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * OpenAI 协议兼容的对话客户端。成稿与选题推荐共用，避免两处各写一份 HTTP 与 JSON 容错。
 */
@Component
public class ChatClient {
    private final AiProperties properties;
    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public ChatClient(AiProperties properties, RestClient aiRestClient) {
        this.properties = properties;
        this.restClient = aiRestClient;
    }

    public boolean available() {
        return properties.configured();
    }

    public String provider() {
        return properties.provider();
    }

    public String model() {
        return properties.model();
    }

    /** 发起一次对话并返回正文文本。 */
    public String complete(String systemPrompt, String userPrompt, double temperature) {
        if (!available()) {
            throw new BadRequestException("尚未配置文案生成模型，请先在节点环境变量设置 AGENT_AI_BASE_URL、"
                    + "AGENT_AI_API_KEY 和 AGENT_AI_MODEL");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", properties.model());
        payload.put("temperature", temperature);
        payload.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));

        JsonNode json;
        try {
            json = restClient.post().uri(properties.base() + "/chat/completions")
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException ex) {
            throw new BadRequestException("调用文案生成模型失败：" + ex.getMessage());
        }
        String text = json == null ? null
                : json.path("choices").path(0).path("message").path("content").asText(null);
        if (!StringUtils.hasText(text)) {
            throw new BadRequestException("文案生成模型返回内容为空");
        }
        return text;
    }

    /**
     * 解析模型返回的 JSON。模型经常会包代码块或在前后加解释，这里按首尾括号裁剪后再解析。
     *
     * @param array true 时按数组裁剪，false 时按对象裁剪
     */
    public JsonNode readJson(String raw, boolean array) {
        String text = raw.trim();
        int start = text.indexOf(array ? '[' : '{');
        int end = text.lastIndexOf(array ? ']' : '}');
        if (start >= 0 && end > start) text = text.substring(start, end + 1);
        try {
            return mapper.readTree(text);
        } catch (Exception ex) {
            throw new BadRequestException("文案生成模型返回的内容不是合法 JSON：" + ex.getMessage());
        }
    }
}
