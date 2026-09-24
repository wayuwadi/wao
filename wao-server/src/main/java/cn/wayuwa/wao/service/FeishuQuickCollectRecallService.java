package cn.wayuwa.wao.service;

import java.time.LocalDateTime;
import java.time.ZoneId;

import cn.wayuwa.wao.domain.FeishuQuickCollectEventEntity;
import cn.wayuwa.wao.mapper.FeishuQuickCollectEventMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 把飞书原消息撤回同步成快速收集任务删除，并保留防止乱序重建的持久墓碑。 */
@Service
public class FeishuQuickCollectRecallService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final FeishuQuickCollectEventMapper events;
    private final TaskService tasks;
    private final KnowledgeService knowledge;

    public FeishuQuickCollectRecallService(FeishuQuickCollectEventMapper events, TaskService tasks,
            KnowledgeService knowledge) {
        this.events = events;
        this.tasks = tasks;
        this.knowledge = knowledge;
    }

    /**
     * 撤回一条飞书原消息。
     *
     * <p>撤回事件可能先于接收事件到达，因此即使尚无收集记录也会先写入 {@code RECALLING} 墓碑；
     * 后到的创建链路看到同一 {@code message_id} 后会停止。已有任务时则在同一事务内删除任务并固化墓碑。
     *
     * @param eventId 飞书撤回事件 id；轮询发现撤回时可为空
     * @param messageId 飞书原消息 id
     * @param chatId 飞书群 id
     * @return 是否识别并记录了有效撤回
     */
    @Transactional
    public boolean recall(String eventId, String messageId, String chatId) {
        if (!StringUtils.hasText(messageId) || !StringUtils.hasText(chatId)) {
            return false;
        }
        String key = "message:" + messageId;
        FeishuQuickCollectEventEntity existing = events.selectById(key);
        if (existing == null) {
            FeishuQuickCollectEventEntity tombstone = new FeishuQuickCollectEventEntity();
            tombstone.setEventKey(key);
            tombstone.setEventId(textOrNull(eventId));
            tombstone.setMessageId(messageId);
            tombstone.setChatId(chatId);
            tombstone.setStatus("RECALLING");
            tombstone.setCreatedAt(now());
            tombstone.setUpdatedAt(tombstone.getCreatedAt());
            try {
                events.insert(tombstone);
                return true;
            } catch (DuplicateKeyException concurrentCreate) {
                existing = events.selectById(key);
            }
        }
        if (existing == null || !chatId.equals(existing.getChatId())) {
            return false;
        }
        if ("RECALLED".equals(existing.getStatus())) {
            return true;
        }
        events.markRecallPending(key, now());
        FeishuQuickCollectEventEntity recalled = events.selectById(key);
        if (recalled != null && StringUtils.hasText(recalled.getTargetId())) {
            if ("NOTE".equals(recalled.getCaptureKind())) {
                knowledge.deleteNoteFromChat(recalled.getTargetId());
            } else {
                tasks.deleteFromChat(recalled.getTargetId());
            }
            events.completeRecall(key, now());
        }
        return true;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(SHANGHAI);
    }

    private static String textOrNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }
}
