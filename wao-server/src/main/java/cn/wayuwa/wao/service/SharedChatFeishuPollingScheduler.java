package cn.wayuwa.wao.service;

import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient.FeishuChatMember;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 共享聊天入站兜底：按持久化秒级配置拉新消息，每分钟回看一天以同步编辑和撤回。 */
@Component
@ConditionalOnProperty(prefix = "wao.feishu.shared-chat", name = "polling-enabled",
        havingValue = "true", matchIfMissing = true)
public class SharedChatFeishuPollingScheduler {
    private static final Logger log = LoggerFactory.getLogger(SharedChatFeishuPollingScheduler.class);
    private static final long LOOKBACK_SECONDS = 120;
    private static final long EDIT_LOOKBACK_SECONDS = 24 * 60 * 60;

    private final SettingService settings;
    private final FeishuOpenApiClient feishu;
    private final SharedChatFeishuBridge bridge;
    private volatile long lastSuccessfulEnd = Instant.now().getEpochSecond();
    private volatile String lastChatId = "";
    private volatile long lastMessagePollNanos;

    public SharedChatFeishuPollingScheduler(SettingService settings, FeishuOpenApiClient feishu,
            SharedChatFeishuBridge bridge) {
        this.settings = settings;
        this.feishu = feishu;
        this.bridge = bridge;
    }

    /** 新消息轮询；水位只有整批成功后才前移。 */
    @Scheduled(fixedDelayString = "${wao.feishu.shared-chat-poll-check-ms:1000}", initialDelay = 1000)
    public void poll() {
        long now = System.nanoTime();
        long interval = bridge.pollIntervalSeconds() * 1_000_000_000L;
        if (lastMessagePollNanos != 0 && now - lastMessagePollNanos < interval) return;
        lastMessagePollNanos = now;
        pollRange(false);
    }

    /** 飞书客户端编辑消息不一定再次触发接收事件，回看一天同步真实终态。 */
    @Scheduled(fixedDelayString = "${wao.feishu.shared-chat-edit-poll-ms:60000}", initialDelay = 30000)
    public void pollEdits() {
        pollRange(true);
    }

    private void pollRange(boolean edits) {
        String appId = settings.get(SettingService.CHAT_SYNC_APP_ID, "");
        String secret = settings.get(SettingService.CHAT_SYNC_APP_SECRET, "");
        String chatId = settings.get(SettingService.SHARED_CHAT_CHAT_ID, "");
        String chatName = settings.get(SettingService.SHARED_CHAT_CHAT_NAME, "飞书群聊");
        if (!StringUtils.hasText(appId) || !StringUtils.hasText(secret) || !StringUtils.hasText(chatId)) return;
        long end = Instant.now().getEpochSecond();
        if (!chatId.equals(lastChatId)) {
            lastChatId = chatId;
            lastSuccessfulEnd = end;
        }
        long start = edits ? end - EDIT_LOOKBACK_SECONDS
                : Math.max(lastSuccessfulEnd - 2, end - LOOKBACK_SECONDS);
        try {
            String token = feishu.tenantAccessToken(appId, secret);
            Map<String, String> names = new LinkedHashMap<>();
            for (FeishuChatMember member : feishu.listChatMembers(token, chatId)) {
                names.put(member.openId(), member.name());
            }
            for (var message : feishu.listMessages(token, chatId, start, end)) {
                bridge.importMessage(token, chatId, chatName, message, names);
            }
            if (!edits) lastSuccessfulEnd = end;
        } catch (RuntimeException exception) {
            log.warn("共享聊天飞书{}轮询失败：{}", edits ? "编辑" : "消息", exception.getMessage());
        }
    }
}
