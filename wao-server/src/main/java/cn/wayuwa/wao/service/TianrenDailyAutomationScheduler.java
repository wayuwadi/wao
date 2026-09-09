package cn.wayuwa.wao.service;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每天上海时间九点触发，分钟扫描负责重启恢复与 AI 离线后的补齐。 */
@Component
public class TianrenDailyAutomationScheduler {
    private static final Logger log = LoggerFactory.getLogger(TianrenDailyAutomationScheduler.class);
    private final TianrenDailyAutomationService automation;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean();

    public TianrenDailyAutomationScheduler(TianrenDailyAutomationService automation,
            @Value("${wao.tianren.daily-scheduler-enabled:true}") boolean enabled) {
        this.automation = automation;
        this.enabled = enabled;
    }

    /** 不阻塞其他定时任务；跨节点互斥由持久化成员锁和既有队列完成。 */
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Shanghai")
    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    public void tick() {
        if (!enabled || !running.compareAndSet(false, true)) return;
        Thread worker = new Thread(() -> {
            try { automation.runDue(); }
            catch (RuntimeException ex) { log.warn("每日自动起卦扫描失败：{}", ex.getClass().getSimpleName()); }
            finally { running.set(false); }
        }, "wao-tianren-daily");
        worker.setDaemon(true);
        worker.start();
    }
}
