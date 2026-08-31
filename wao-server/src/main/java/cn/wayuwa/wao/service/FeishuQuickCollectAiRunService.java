package cn.wayuwa.wao.service;

import cn.wayuwa.wao.domain.AccountEntity;
import cn.wayuwa.wao.domain.AccountIdentityEntity;
import cn.wayuwa.wao.domain.FeishuQuickCollectAiRunTriggerEntity;
import cn.wayuwa.wao.domain.FeishuQuickCollectEventEntity;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.AiRunDto;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.mapper.AccountIdentityMapper;
import cn.wayuwa.wao.mapper.AccountMapper;
import cn.wayuwa.wao.mapper.FeishuQuickCollectAiRunTriggerMapper;
import cn.wayuwa.wao.mapper.FeishuQuickCollectEventMapper;
import cn.wayuwa.wao.mapper.TaskMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 把快速收集原消息上的用户触发表情转换为一次真实、幂等且有身份边界的 AI Run。 */
@Service
public class FeishuQuickCollectAiRunService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Logger log = LoggerFactory.getLogger(FeishuQuickCollectAiRunService.class);

    private final FeishuQuickCollectEventMapper collectEvents;
    private final FeishuQuickCollectAiRunTriggerMapper triggers;
    private final AccountIdentityMapper identities;
    private final AccountMapper accounts;
    private final TaskMapper tasks;
    private final AiRunService aiRuns;
    private final SettingService settings;
    private final FeishuOpenApiClient feishu;
    private final FeishuQuickCollectReactionPolicy reactionPolicy;

    public FeishuQuickCollectAiRunService(FeishuQuickCollectEventMapper collectEvents,
            FeishuQuickCollectAiRunTriggerMapper triggers, AccountIdentityMapper identities,
            AccountMapper accounts, TaskMapper tasks, AiRunService aiRuns, SettingService settings,
            FeishuOpenApiClient feishu, FeishuQuickCollectReactionPolicy reactionPolicy) {
        this.collectEvents = collectEvents;
        this.triggers = triggers;
        this.identities = identities;
        this.accounts = accounts;
        this.tasks = tasks;
        this.aiRuns = aiRuns;
        this.settings = settings;
        this.feishu = feishu;
        this.reactionPolicy = reactionPolicy;
    }

    /**
     * 消费一次 reaction.created 事件；只有原消息创建者本人添加当前配置的触发表情时才派工。
     *
     * @param body 已完成飞书签名校验/解密的事件体
     * @param baseUrl 当前 WAO 公网根地址，用于生成 Runner 可读的 Markdown 地址
     * @return true 表示事件是快速收集任务上的表情触发（包括重复事件）
     */
    @Transactional
    public boolean trigger(JsonNode body, String baseUrl) {
        if (!"im.message.reaction.created_v1".equals(body.path("header").path("event_type").asText(""))) {
            return false;
        }
        JsonNode event = body.path("event");
        if (!"user".equals(event.path("operator_type").asText(""))
                || !reactionPolicy.receivedEmoji()
                        .equals(event.path("reaction_type").path("emoji_type").asText(""))) {
            return false;
        }
        String messageId = event.path("message_id").asText("");
        FeishuQuickCollectEventEntity collected = StringUtils.hasText(messageId)
                ? collectEvents.findCreatedByMessageId(messageId) : null;
        if (collected == null) return false;

        String operatorOpenId = event.path("user_id").path("open_id").asText("");
        String operatorSubject = StringUtils.hasText(operatorOpenId) ? operatorOpenId
                : event.path("user_id").path("union_id").asText("");
        String operatorMemberId = memberId(event.path("user_id"));
        TaskEntity task = tasks.selectById(collected.getTargetId());
        String creatorId = task == null ? null : task.getCreatedBy();
        if (!StringUtils.hasText(creatorId) && task != null) creatorId = task.getOwnerId();
        if (!StringUtils.hasText(operatorMemberId) || !operatorMemberId.equals(creatorId)) {
            log.warn("忽略非任务创建者添加的飞书 AI Run 触发表情，消息 {}", messageId);
            return true;
        }

        String eventId = body.path("header").path("event_id").asText("");
        String eventKey = eventKey(eventId, messageId, operatorSubject,
                event.path("action_time").asText(""));
        FeishuQuickCollectAiRunTriggerEntity trigger = new FeishuQuickCollectAiRunTriggerEntity();
        trigger.setEventKey(eventKey);
        trigger.setEventId(empty(eventId));
        trigger.setMessageId(messageId);
        trigger.setOperatorOpenId(operatorSubject);
        trigger.setTaskId(collected.getTargetId());
        trigger.setAiRunId(null);
        trigger.setCreatedAt(LocalDateTime.now(SHANGHAI));
        try {
            triggers.insert(trigger);
        } catch (DuplicateKeyException duplicate) {
            return true;
        }

        try {
            List<AiRunDto> created = aiRuns.enqueue(List.of(collected.getTargetId()), null, null, null,
                    null, null, null, true, false, operatorMemberId, baseUrl);
            if (created.isEmpty()) {
                // 已有活动运行时 enqueue 会幂等返回空；事件记录仍保留，防止同一次回调晚到后再次派工。
                return true;
            }
            trigger.setAiRunId(created.get(0).id());
            triggers.updateById(trigger);
            return true;
        } catch (RuntimeException ex) {
            addErrorReaction(messageId);
            throw ex;
        }
    }

    private String memberId(JsonNode ids) {
        return memberId(ids.path("open_id").asText(""), ids.path("union_id").asText(""));
    }

    private String memberId(String openId, String unionId) {
        AccountIdentityEntity identity = StringUtils.hasText(openId)
                ? identities.findByProviderSubject("feishu", openId) : null;
        if (identity == null && StringUtils.hasText(unionId)) {
            identity = identities.findByProviderUnionSubject("feishu", unionId);
        }
        if (identity == null) return null;
        AccountEntity account = accounts.selectById(identity.getAccountId());
        return account == null ? null : account.getMemberId();
    }

    private void addErrorReaction(String messageId) {
        try {
            String appId = settings.get(SettingService.CHAT_SYNC_APP_ID, "");
            String secret = settings.get(SettingService.CHAT_SYNC_APP_SECRET, "");
            if (StringUtils.hasText(appId) && StringUtils.hasText(secret)) {
                feishu.addMessageReaction(feishu.tenantAccessToken(appId, secret), messageId, "ERROR");
            }
        } catch (RuntimeException reactionError) {
            log.warn("飞书飞机触发 AI Run 失败，错误表情也未能添加：{}", reactionError.getMessage());
        }
    }

    private static String eventKey(String eventId, String messageId, String openId, String actionTime) {
        if (StringUtils.hasText(eventId)) return "event:" + eventId;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((messageId + "\n" + openId + "\n" + actionTime).getBytes(StandardCharsets.UTF_8));
            return "reaction:" + HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("无法生成飞书表情事件去重键", ex);
        }
    }

    private static String empty(String value) {
        return StringUtils.hasText(value) ? value : null;
    }
}
