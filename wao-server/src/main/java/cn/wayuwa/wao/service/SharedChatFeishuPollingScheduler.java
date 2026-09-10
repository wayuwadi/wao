package cn.wayuwa.wao.service;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import java.util.UUID;
import cn.wayuwa.wao.mapper.SharedChatSyncStateMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 共享聊天入站兜底：按持久化秒级配置拉新消息，每五分钟回看一天以同步编辑和撤回。 */
@Component
@ConditionalOnProperty(prefix = "wao.feishu.shared-chat", name = "polling-enabled",
        havingValue = "true", matchIfMissing = true)
public class SharedChatFeishuPollingScheduler {
    private static final Logger log = LoggerFactory.getLogger(SharedChatFeishuPollingScheduler.class);
    private static final long LOOKBACK_SECONDS = 120;
    private static final long EDIT_LOOKBACK_SECONDS = 24 * 60 * 60;

    private final SettingService settings;
    private final SharedChatFeishuBridge bridge;
    private final SharedChatSynchronizationService synchronization;
    private final SharedChatSyncStateMapper state;
    private final String leaseOwner = UUID.randomUUID().toString().replace("-", "");
    private volatile long lastMessagePollNanos;
    @Value("${wao.feishu.shared-chat-edit-poll-ms:300000}")
    private long editPollMs = 300000;

    public SharedChatFeishuPollingScheduler(SettingService settings, SharedChatFeishuBridge bridge,
            SharedChatSynchronizationService synchronization, SharedChatSyncStateMapper state) {
        this.settings = settings;
        this.bridge = bridge;
        this.synchronization = synchronization;
        this.state = state;
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
    @Scheduled(fixedDelayString = "${wao.feishu.shared-chat-edit-poll-ms:300000}", initialDelay = 30000)
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
        String key = edits ? "edits" : "realtime";
        state.prepare(key, chatId, end);
        if (state.claimPolling(key, leaseOwner, edits ? editPollMs * 1000 : bridge.pollIntervalSeconds() * 1_000_000L) != 1) return;
        Long cursor = state.cursor(key);
        long start = edits ? end - EDIT_LOOKBACK_SECONDS
                : Math.max((cursor == null ? end : cursor) - 2, end - LOOKBACK_SECONDS);
        try {
            synchronization.synchronize(start, end);
            state.complete(key, leaseOwner, end);
        } catch (RuntimeException exception) {
            state.release(key, leaseOwner);
            log.warn("共享聊天飞书{}轮询失败：{}", edits ? "编辑" : "消息", exception.getMessage());
        }
    }
}
