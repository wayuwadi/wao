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

    private static final Logger log = LoggerFactory.getLogger(FeishuAiRunReactionNotifier.class);

    private final FeishuQuickCollectEventMapper events;
    private final SettingService settings;
    private final FeishuOpenApiClient feishu;
    private final FeishuQuickCollectReactionPolicy reactionPolicy;

    public FeishuAiRunReactionNotifier(FeishuQuickCollectEventMapper events, SettingService settings,
            FeishuOpenApiClient feishu, FeishuQuickCollectReactionPolicy reactionPolicy) {
        this.events = events;
        this.settings = settings;
        this.feishu = feishu;
        this.reactionPolicy = reactionPolicy;
    }

    /** AI Run 成功入队后给原消息添加当前配置的“执行中”表情。 */
    public void reactAfterEnqueued(Collection<String> taskIds) {
        reactAfterCommit(taskIds, reactionPolicy.triggerEmoji(), "执行中");
    }

    /**
     * AI Run 成功后给对应的飞书原消息加完成勾；没有原消息或表情接口失败时保持静默。
     *
     * @param taskIds 本次成功运行覆盖的任务 id
     */
    public void reactAfterSucceeded(Collection<String> taskIds) {
        reactAfterCommit(taskIds, reactionPolicy.completedEmoji(), "已完成待验收");
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
