package cn.wayuwa.wao.service;

import java.util.Collection;
import java.util.List;

import cn.wayuwa.wao.domain.FeishuQuickCollectEventEntity;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.mapper.FeishuQuickCollectEventMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

/** 给通过飞书群聊快速收集创建的任务原消息反馈 AI Run 进度。 */
@Service
public class FeishuAiRunReactionNotifier {

    /** 飞书官方表情枚举中与飞机图标对应的类型。 */
    static final String AI_RUN_STARTED_EMOJI_TYPE = "StatusInFlight";

    /** 飞书官方表情枚举中用于区分 AI 完成与任务已收集 {@code DONE} 的勾选类型。 */
    static final String AI_RUN_SUCCEEDED_EMOJI_TYPE = "CheckMark";

    private static final Logger log = LoggerFactory.getLogger(FeishuAiRunReactionNotifier.class);

    private final FeishuQuickCollectEventMapper events;
    private final SettingService settings;
    private final FeishuOpenApiClient feishu;

    public FeishuAiRunReactionNotifier(FeishuQuickCollectEventMapper events, SettingService settings,
            FeishuOpenApiClient feishu) {
        this.events = events;
        this.settings = settings;
        this.feishu = feishu;
    }

    /**
     * AI Run 成功入队后给对应的飞书原消息加飞机表情；事务回滚、网页创建的任务和表情接口失败均不误报。
     *
     * @param taskIds 本次真正创建运行的任务 id
     */
    public void reactAfterEnqueued(Collection<String> taskIds) {
        reactAfterCommit(taskIds, AI_RUN_STARTED_EMOJI_TYPE, "已派工");
    }

    /**
     * AI Run 成功后给对应的飞书原消息加完成勾；没有原消息或表情接口失败时保持静默。
     *
     * @param taskIds 本次成功运行覆盖的任务 id
     */
    public void reactAfterSucceeded(Collection<String> taskIds) {
        reactAfterCommit(taskIds, AI_RUN_SUCCEEDED_EMOJI_TYPE, "已完成");
    }

    private void reactAfterCommit(Collection<String> taskIds, String emojiType, String stage) {
        List<String> targets = taskIds == null ? List.of() : taskIds.stream()
                .filter(StringUtils::hasText).distinct().toList();
        if (targets.isEmpty()) {
            return;
        }
        Runnable reaction = () -> react(targets, emojiType, stage);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    reaction.run();
                }
            });
        } else {
            reaction.run();
        }
    }

    private void react(List<String> taskIds, String emojiType, String stage) {
        String appId = settings.get(SettingService.CHAT_SYNC_APP_ID, "");
        String appSecret = settings.get(SettingService.CHAT_SYNC_APP_SECRET, "");
        if (!StringUtils.hasText(appId) || !StringUtils.hasText(appSecret)) {
            return;
        }
        List<String> messageIds;
        try {
            messageIds = events.selectList(new LambdaQueryWrapper<FeishuQuickCollectEventEntity>()
                            .in(FeishuQuickCollectEventEntity::getTargetId, taskIds)
                            .isNotNull(FeishuQuickCollectEventEntity::getMessageId))
                    .stream().map(FeishuQuickCollectEventEntity::getMessageId)
                    .filter(StringUtils::hasText).distinct().toList();
        } catch (RuntimeException ex) {
            log.warn("查询飞书快速收集原消息失败，未添加 AI Run {}表情：{}", stage, ex.getMessage());
            return;
        }
        if (messageIds.isEmpty()) {
            return;
        }
        String token;
        try {
            token = feishu.tenantAccessToken(appId, appSecret);
        } catch (RuntimeException ex) {
            log.warn("获取飞书应用令牌失败，未添加 AI Run {}表情：{}", stage, ex.getMessage());
            return;
        }
        for (String messageId : messageIds) {
            try {
                feishu.addMessageReaction(token, messageId, emojiType);
            } catch (RuntimeException ex) {
                log.warn("飞书快速收集消息添加 AI Run {}表情失败：{}", stage, ex.getMessage());
            }
        }
    }
}
