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
 * <p>现有群聊归档已具备读取目标群消息的真实应用权限。这里每 60 秒只读近两分钟，
 * 并只交给收集服务“用户发送、精确 @ 当前应用机器人”的消息；已撤回消息则按 message_id 删除
 * 原任务。Linux / Windows 同时扫到也由持久事件状态保证幂等。回调链路仍保留，两种入口共用去重。
 */
@Component
public class FeishuQuickCollectPollingScheduler {

    private static final Logger log = LoggerFactory.getLogger(FeishuQuickCollectPollingScheduler.class);
    private static final long LOOKBACK_SECONDS = 120;
    private final SettingService settings;
    private final FeishuOpenApiClient feishu;
    private final FeishuQuickCollectService quickCollect;
    private final SharedChatSyncStateMapper state;
    private final String leaseOwner = UUID.randomUUID().toString().replace("-", "");
    @Value("${wao.feishu.quick-collect-poll-ms:60000}")
    private long pollMs = 60000;
    private volatile String botOpenId;
    private volatile boolean readyLogged;

    public FeishuQuickCollectPollingScheduler(SettingService settings, FeishuOpenApiClient feishu,
            FeishuQuickCollectService quickCollect, SharedChatSyncStateMapper state) {
        this.state = state;
        this.settings = settings;
        this.feishu = feishu;
        this.quickCollect = quickCollect;
    }

    /** 定时读取新消息；单次失败保留旧水位，下次仍有最多两分钟的恢复窗口。 */
    @Scheduled(fixedDelayString = "${wao.feishu.quick-collect-poll-ms:60000}", initialDelay = 60000)
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
        long start = Math.max((cursor == null ? end : cursor) - 2, end - LOOKBACK_SECONDS);
        try {
            String token = feishu.tenantAccessToken(appId, appSecret);
            String currentBot = botOpenId;
            if (!StringUtils.hasText(currentBot)) {
                currentBot = feishu.botOpenId(token);
                botOpenId = currentBot;
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
                                message.messageId(), ex.getMessage());
                    }
                } else if (eligible(message, currentBot)) {
                    try {
                        quickCollect.collectPolled(chatId, message);
                        quickCollect.triggerPolledReaction(token, message.messageId());
                    } catch (RuntimeException ex) {
                        log.warn("飞书轮询收集或表情派工失败：message={} reason={}",
                                message.messageId(), ex.getMessage());
                    }
                }
            }
            state.complete("quick-realtime", leaseOwner, end);
        } catch (RuntimeException ex) {
            botOpenId = null;
            readyLogged = false;
            log.warn("飞书个人收集轮询失败：{}", ex.getMessage());
        } finally {
            state.release("quick-realtime", leaseOwner);
        }
    }

    static boolean eligible(FeishuMessage message, String botOpenId) {
        return message != null && "user".equals(message.senderType()) && !message.deleted()
                && StringUtils.hasText(message.messageId())
                && message.mentions().stream().anyMatch(mention -> botOpenId.equals(mention.openId()));
    }
}
