package cn.wayuwa.wao.service;

import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;

/** 定时扫描人工填写的到期日，本地验收模式不投递群消息。 */
@Component
@ConditionalOnProperty(name = "wao.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class CodexMembershipScheduler {
    private final CodexMembershipService service;
    public CodexMembershipScheduler(CodexMembershipService service) { this.service = service; }
    /** 多节点通过服务内行锁和按到期日记录的投递状态去重。 */
    @Scheduled(fixedDelay = 1_800_000, initialDelay = 90_000)
    public void remind() {
        try { service.sendDueReminders(LocalDate.now(ZoneId.of("Asia/Shanghai"))); }
        catch (RuntimeException error) { LoggerFactory.getLogger(getClass()).warn("Codex 会员提醒暂未完成，下轮重试：{}", error.getClass().getSimpleName()); }
    }
}
