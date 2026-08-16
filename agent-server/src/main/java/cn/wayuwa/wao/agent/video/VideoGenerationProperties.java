package cn.wayuwa.wao.agent.video;

import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/** OpenAI Videos API 的私有节点配置与公开能力边界。 */
@ConfigurationProperties(prefix = "wao-agent.video")
public record VideoGenerationProperties(String baseUrl, String apiKey, String model,
                                        Integer timeoutMs, Long pollDelayMs) {
    public static final LocalDate SHUTDOWN_DATE = LocalDate.of(2026, 9, 24);
    public static final List<String> MODELS = List.of("sora-2", "sora-2-pro");
    public static final List<Integer> DURATIONS = List.of(4, 8, 12);
    public static final List<String> SIZES = List.of("720x1280", "1280x720", "1024x1792", "1792x1024");

    public boolean configured() {
        return StringUtils.hasText(apiKey) && StringUtils.hasText(baseUrl) && StringUtils.hasText(defaultModel());
    }

    public boolean available() {
        return configured() && LocalDate.now().isBefore(SHUTDOWN_DATE);
    }

    public String base() {
        String value = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        return value.endsWith("/v1") ? value : value + "/v1";
    }

    public String defaultModel() {
        return StringUtils.hasText(model) ? model.trim() : "sora-2";
    }

    public int timeout() {
        return timeoutMs == null || timeoutMs <= 0 ? 120_000 : timeoutMs;
    }
}
