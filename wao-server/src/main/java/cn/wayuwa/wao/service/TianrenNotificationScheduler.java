package cn.wayuwa.wao.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 双节点可执行的有界投递扫描，数据库条件更新防止同时发送。 */
@Component
public class TianrenNotificationScheduler {
    private static final Logger log = LoggerFactory.getLogger(TianrenNotificationScheduler.class);
    private final TianrenNotificationService notifications;
    private final boolean enabled;
    private final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean();

    public TianrenNotificationScheduler(TianrenNotificationService notifications,
            @Value("${wao.tianren.notification-scheduler-enabled:true}") boolean enabled) {
        this.notifications = notifications;
        this.enabled = enabled;
    }

    /** 按固定间隔恢复当天未发送卡片；失败不终止下次扫描。 */
    @Scheduled(initialDelay = 15_000, fixedDelay = 15_000)
    public void tick() {
        if (!enabled || !running.compareAndSet(false, true)) return;
        Thread worker = new Thread(() -> {
            try { notifications.tick(); }
            catch (RuntimeException ex) { log.warn("卦象通知扫描失败：{}", ex.getClass().getSimpleName()); }
            finally { running.set(false); }
        }, "wao-tianren-notification");
        worker.setDaemon(true);
        worker.start();
    }
}
