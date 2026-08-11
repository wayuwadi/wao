package cn.wayuwa.wao.agent.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * 文案生成模型配置，沿用 WAO 已在用的 OpenAI 协议兼容自定义 API 形态。
 *
 * <p>DeepSeek、通义、豆包等都兼容该协议，换供应商只需改 baseUrl/model/apiKey。
 * WAO 里的 Claude Code、Codex 走的是订阅式 OAuth，只能给 Runner 跑 CLI，不能在服务端直接调，所以不在这里复用。
 *
 * @param provider  供应商标识，仅用于展示
 * @param baseUrl   API 根地址，需以 /v1 结尾
 * @param apiKey    API Key，留空表示未接入
 * @param model     模型 id
 * @param timeoutMs 单次请求超时
 */
@ConfigurationProperties(prefix = "wao-agent.ai")
public record AiProperties(String provider, String baseUrl, String apiKey, String model, Integer timeoutMs) {

    public boolean configured() {
        return StringUtils.hasText(apiKey) && StringUtils.hasText(baseUrl) && StringUtils.hasText(model);
    }

    public String base() {
        String value = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        return value.endsWith("/v1") ? value : value + "/v1";
    }

    public int timeout() {
        return timeoutMs == null || timeoutMs <= 0 ? 60_000 : timeoutMs;
    }
}
