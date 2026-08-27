package cn.wayuwa.wao.service;

import cn.wayuwa.wao.domain.AutomationJobEntity;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/** 每日核对前一日共享聊天，并把真实变更量写入自动化运行。 */
@Component
public class SharedChatNightlySyncJobHandler implements JobHandler {
    static final String HANDLER_NAME = "shared-chat-nightly-sync";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final SharedChatSynchronizationService synchronization;

    public SharedChatNightlySyncJobHandler(SharedChatSynchronizationService synchronization) {
        this.synchronization = synchronization;
    }

    @Override public String name() { return HANDLER_NAME; }
    @Override public String execute() { return execute(null, null); }

    /** 计划任务核对计划触发日前一天；页面手动运行时核对当天。 */
    @Override
    public String execute(AutomationJobEntity job, LocalDateTime scheduledFireAt) {
        LocalDate day = scheduledFireAt == null
                ? LocalDate.now(SHANGHAI) : scheduledFireAt.toLocalDate().minusDays(1);
        var run = synchronization.synchronizeDay(day, "NIGHTLY");
        return "成功 · " + day + " 扫描 " + run.messagesScanned() + " 条，新增 "
                + run.messagesInserted() + " 条，更新 " + run.messagesUpdated()
                + " 条，可归因存储增量 " + run.contentBytesAdded() + " 字节";
    }
}
