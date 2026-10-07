package cn.wayuwa.wao.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient.FeishuMessage;
import cn.wayuwa.wao.mapper.SharedChatSyncStateMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 飞书个人快速收集的主动轮询入口。
 *
 * <p>沿用飞书应用的群消息读取权限，每十分钟读取近期消息；仅收集用户精确 @ 当前应用
 * 或专用「任务待办」「小记创作」机器人的消息。撤回按 message_id 清理原内容，
 * 多节点轮询由持久事件状态保证幂等。
 */
@Component
public class FeishuQuickCollectPollingScheduler {

    private static final Logger log = LoggerFactory.getLogger(FeishuQuickCollectPollingScheduler.class);
    private static final long LOOKBACK_SECONDS = 600;
    private final SettingService settings;
    private final FeishuOpenApiClient feishu;
    private final FeishuQuickCollectService quickCollect;
    private final SharedChatSyncStateMapper state;
    private final String leaseOwner = UUID.randomUUID().toString().replace("-", "");
    @Value("${wao.feishu.quick-collect-poll-ms:600000}")
    private long pollMs = 600000;
    private volatile String botOpenId;
    private volatile String botAppId;
    private int consecutiveFailures;
    private volatile long nextBotLookupAt;
    private volatile boolean readyLogged;

    public FeishuQuickCollectPollingScheduler(SettingService settings, FeishuOpenApiClient feishu,
            FeishuQuickCollectService quickCollect, SharedChatSyncStateMapper state) {
        this.state = state;
        this.settings = settings;
        this.feishu = feishu;
        this.quickCollect = quickCollect;
    }

    /** 定时读取新消息；单次失败保留旧水位并共享冷却，不自动补扫历史记录。 */
    @Scheduled(fixedDelayString = "${wao.feishu.quick-collect-poll-ms:600000}", initialDelay = 60000)
    public void poll() {
        boolean quickEnabled = settings.getBoolean(SettingService.QUICK_COLLECT_ENABLED);
        if (!quickEnabled) {
            readyLogged = false;
            return;
        }
        String appId = settings.get(SettingService.CHAT_SYNC_APP_ID, "");
        String appSecret = settings.get(SettingService.CHAT_SYNC_APP_SECRET, "");
        String chatId = settings.get(SettingService.CHAT_SYNC_CHAT_ID, "");
        if (!StringUtils.hasText(appId) || !StringUtils.hasText(appSecret) || !StringUtils.hasText(chatId)) {
            return;
        }
        long end = Instant.now().getEpochSecond();
        state.prepare("quick-realtime", appId + ":" + chatId, end);
        if (state.claimPolling("quick-realtime", leaseOwner, pollMs * 1000) != 1) return;
        Long cursor = state.cursor("quick-realtime");
        long start = Math.max((cursor == null ? end - LOOKBACK_SECONDS : cursor) - 2,
                end - Math.max(LOOKBACK_SECONDS, pollMs / 1000 + 120));
        boolean deferred = false;
        try {
            String token = feishu.tenantAccessToken(appId, appSecret);
            if (!appId.equals(botAppId)) {
                botOpenId = null;
                botAppId = appId;
                nextBotLookupAt = 0;
            }
            String currentBot = botOpenId;
            if (!StringUtils.hasText(currentBot) && end >= nextBotLookupAt) {
                try {
                    currentBot = feishu.botOpenId(token);
                    botOpenId = currentBot;
                } catch (RuntimeException ex) {
                    // 群自定义机器人有独立 @ 名称；旧应用的 Bot Info 失败不应阻断它们。
                    nextBotLookupAt = end + 3600;
                    log.warn("飞书旧机器人身份暂不可读，本轮继续识别专用机器人：{}", ex.getClass().getSimpleName());
                }
            }
            List<FeishuMessage> messages = feishu.listMessages(token, chatId, start, end);
            if (!readyLogged) {
                log.info("飞书群消息主动轮询已就绪：已认证应用并可读取目标群");
                readyLogged = true;
            }
            for (FeishuMessage message : messages) {
                if (message != null && message.deleted()) {
                    try {
                        quickCollect.recallPolled(chatId, message.messageId());
                    } catch (RuntimeException ex) {
                        log.warn("飞书轮询撤回同步失败：message={} reason={}",
                                message.messageId(), ex.getClass().getSimpleName());
                    }
                } else if (eligible(message, currentBot)) {
                    try {
                        quickCollect.collectPolled(chatId, message);
                        quickCollect.triggerPolledReaction(token, message.messageId());
                    } catch (RuntimeException ex) {
                        log.warn("飞书轮询收集或表情派工失败：message={} reason={}",
                                message.messageId(), ex.getClass().getSimpleName());
                    }
                }
            }
            state.complete("quick-realtime", leaseOwner, end);
            consecutiveFailures = 0;
        } catch (RuntimeException ex) {
            readyLogged = false;
            consecutiveFailures = Math.min(6, consecutiveFailures + 1);
            long cooldown = Math.min(21600, 600L << (consecutiveFailures - 1));
            if (cn.wayuwa.wao.feishu.FeishuApiException.monthlyQuotaExceeded(ex)) {
                var now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
                cooldown = java.time.Duration.between(now, now.toLocalDate().withDayOfMonth(1)
                        .plusMonths(1).atStartOfDay(now.getZone())).getSeconds() + 60;
            }
            deferred = state.defer("quick-realtime", leaseOwner, cooldown) == 1;
            log.warn("飞书个人收集轮询失败，冷却 {} 秒：{}", cooldown, ex.getClass().getSimpleName());
        } finally {
            if (!deferred) state.release("quick-realtime", leaseOwner);
        }
    }

    static boolean eligible(FeishuMessage message, String botOpenId) {
        return message != null && "user".equals(message.senderType()) && !message.deleted()
                && StringUtils.hasText(message.messageId())
                && !message.mentions().isEmpty();
    }
}
