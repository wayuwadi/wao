package cn.wayuwa.wao.feishu;

import java.util.Map;

import cn.wayuwa.wao.service.SettingService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 飞书群机器人通知：全局一个 webhook，系统里所有需要「说一声」的地方都走它。
 *
 * <p>地址由用户在「设置 → 通知」里填，加密存 {@code system_setting}（见 {@link SettingService}），
 * 因此既不进 Git 也不必改环境变量重启。
 */
@Component
public class FeishuWebhookNotifier {

    private static final Logger log = LoggerFactory.getLogger(FeishuWebhookNotifier.class);

    private final SettingService settings;
    private final RestClient client;

    public FeishuWebhookNotifier(SettingService settings, RestClient.Builder builder) {
        this.settings = settings;
        this.client = builder.build();
    }

    /** 通知是否已配置且启用。 */
    public boolean enabled() {
        String url = settings.get(SettingService.FEISHU_WEBHOOK_URL);
        return url != null && !url.isBlank() && settings.getBoolean(SettingService.FEISHU_WEBHOOK_ENABLED);
    }

    /**
     * 按当前配置发一条文本通知；没配置或未启用时直接跳过。
     *
     * @param text 通知正文
     * @return 是否真的发出去了
     */
    public boolean notifyText(String text) {
        if (!enabled()) {
            return false;
        }
        try {
            send(settings.get(SettingService.FEISHU_WEBHOOK_URL), text);
            return true;
        } catch (RuntimeException ex) {
            // 通知失败不该影响触发它的业务动作，记一条日志就够了
            log.warn("飞书 webhook 通知发送失败：{}", ex.getMessage());
            return false;
        }
    }

    /**
     * 往指定 webhook 发一条文本消息（「发送测试消息」按钮直接用它，不受启用开关影响）。
     *
     * @param url  webhook 地址
     * @param text 通知正文
     * @throws FeishuApiException 地址为空、网络不通或飞书返回错误码时
     */
    public void send(String url, String text) {
        if (url == null || url.isBlank()) {
            throw new FeishuApiException("请先填写飞书 webhook 地址");
        }
        JsonNode res;
        try {
            res = client.post().uri(url).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("msg_type", "text", "content", Map.of("text", text)))
                    .retrieve().body(JsonNode.class);
        } catch (RestClientException ex) {
            throw new FeishuApiException("无法连接飞书 webhook，请检查地址是否正确", ex);
        }
        int code = res == null ? -1 : res.path("code").asInt(res.path("StatusCode").asInt(0));
        if (code != 0) {
            throw new FeishuApiException("飞书 webhook 返回错误：" + (res == null ? "空响应" : res.path("msg").asText("")));
        }
    }
}
