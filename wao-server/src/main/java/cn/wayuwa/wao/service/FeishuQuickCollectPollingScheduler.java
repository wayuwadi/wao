package cn.wayuwa.wao.service;

import java.time.Instant;
import java.util.List;

import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient.FeishuMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 飞书个人快速收集的主动轮询入口。
 *
 * <p>现有群聊归档已具备读取目标群消息的真实应用权限。这里每 15 秒只读近两分钟，
 * 并只交给收集服务“用户发送、未撤回、精确 @ 当前应用机器人”的消息。Linux / Windows
 * 同时扫到也由 message_id 唯一键保证只创建一次。回调链路仍保留，两种入口共用去重。
 */
@Component
public class FeishuQuickCollectPollingScheduler {

    private static final Logger log = LoggerFactory.getLogger(FeishuQuickCollectPollingScheduler.class);
    private static final long LOOKBACK_SECONDS = 120;
    /** 飞书客户端允许用户在发送后一段时间继续编辑，回看一天覆盖该真实交互窗口。 */
    private static final long EDIT_LOOKBACK_SECONDS = 24 * 60 * 60;

    private final SettingService settings;
    private final FeishuOpenApiClient feishu;
    private final FeishuQuickCollectService quickCollect;
    private volatile long lastSuccessfulEnd = Instant.now().getEpochSecond();
    private volatile String botOpenId;
    private volatile boolean readyLogged;

    public FeishuQuickCollectPollingScheduler(SettingService settings, FeishuOpenApiClient feishu,
            FeishuQuickCollectService quickCollect) {
        this.settings = settings;
        this.feishu = feishu;
        this.quickCollect = quickCollect;
    }

    /** 定时读取新消息；单次失败保留旧水位，下次仍有最多两分钟的恢复窗口。 */
    @Scheduled(fixedDelayString = "${wao.feishu.quick-collect-poll-ms:15000}", initialDelay = 15000)
    public void poll() {
        if (!settings.getBoolean(SettingService.QUICK_COLLECT_ENABLED)) {
            lastSuccessfulEnd = Instant.now().getEpochSecond();
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
        long start = Math.max(lastSuccessfulEnd - 2, end - LOOKBACK_SECONDS);
        try {
            String token = feishu.tenantAccessToken(appId, appSecret);
            String currentBot = botOpenId;
            if (!StringUtils.hasText(currentBot)) {
                currentBot = feishu.botOpenId(token);
                botOpenId = currentBot;
            }
            List<FeishuMessage> messages = feishu.listMessages(token, chatId, start, end);
            if (!readyLogged) {
                log.info("飞书个人收集轮询已就绪：已认证机器人并可读取目标群");
                readyLogged = true;
            }
            for (FeishuMessage message : messages) {
                if (eligible(message, currentBot)) {
                    try {
                        quickCollect.collectPolled(chatId, message);
                    } catch (RuntimeException ex) {
                        log.warn("飞书轮询收集单条消息失败：message={} reason={}",
                                message.messageId(), ex.getMessage());
                    }
                }
            }
            lastSuccessfulEnd = end;
        } catch (RuntimeException ex) {
            botOpenId = null;
            readyLogged = false;
            log.warn("飞书个人收集轮询失败：{}", ex.getMessage());
        }
    }

    /**
     * 每分钟回看近 24 小时消息，捕获飞书不会再次投递接收事件的客户端编辑。
     *
     * <p>内容是否变化、双节点谁负责更新由数据库中的消息指纹原子判定；这里读取当前正文，
     * 不用创建时间或随机数据推测编辑结果。
     */
    @Scheduled(fixedDelayString = "${wao.feishu.quick-collect-edit-poll-ms:60000}", initialDelay = 30000)
    public void pollEdits() {
        if (!settings.getBoolean(SettingService.QUICK_COLLECT_ENABLED)) {
            return;
        }
        String appId = settings.get(SettingService.CHAT_SYNC_APP_ID, "");
        String appSecret = settings.get(SettingService.CHAT_SYNC_APP_SECRET, "");
        String chatId = settings.get(SettingService.CHAT_SYNC_CHAT_ID, "");
        if (!StringUtils.hasText(appId) || !StringUtils.hasText(appSecret) || !StringUtils.hasText(chatId)) {
            return;
        }
        long end = Instant.now().getEpochSecond();
        try {
            String token = feishu.tenantAccessToken(appId, appSecret);
            String currentBot = botOpenId;
            if (!StringUtils.hasText(currentBot)) {
                currentBot = feishu.botOpenId(token);
                botOpenId = currentBot;
            }
            for (FeishuMessage message : feishu.listMessages(token, chatId, end - EDIT_LOOKBACK_SECONDS, end)) {
                if (eligible(message, currentBot)) {
                    try {
                        quickCollect.collectPolled(chatId, message);
                    } catch (RuntimeException ex) {
                        log.warn("飞书编辑消息同步失败：message={} reason={}",
                                message.messageId(), ex.getMessage());
                    }
                }
            }
        } catch (RuntimeException ex) {
            botOpenId = null;
            log.warn("飞书编辑消息回看失败：{}", ex.getMessage());
        }
    }

    static boolean eligible(FeishuMessage message, String botOpenId) {
        return message != null && "user".equals(message.senderType()) && !message.deleted()
                && StringUtils.hasText(message.messageId())
                && message.mentions().stream().anyMatch(mention -> botOpenId.equals(mention.openId()));
    }
}
