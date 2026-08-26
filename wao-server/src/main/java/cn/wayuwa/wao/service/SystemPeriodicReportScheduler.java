package cn.wayuwa.wao.service;

import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 只在指定节点按团队共享设置刷新系统日报、周报和月报。 */
@Component
@ConditionalOnProperty(name = "wao.system-report.scheduler-enabled", havingValue = "true")
public class SystemPeriodicReportScheduler {
    private static final Logger log = LoggerFactory.getLogger(SystemPeriodicReportScheduler.class);
    private final SystemPeriodicReportService reports;

    public SystemPeriodicReportScheduler(SystemPeriodicReportService reports) {
        this.reports = reports;
    }

    /** 应用启动后补齐最近结束的日、周、月，避免午夜停机造成永久缺报。 */
    @EventListener(ApplicationReadyEvent.class)
    public void prepareAfterStartup() {
        prepare("启动补齐");
    }

    /** 每分钟检查持久化刷新时间与频率；未命中时不查询报告数据。 */
    @Scheduled(cron = "0 * * * * *", zone = "Asia/Shanghai")
    public void prepareConfiguredReports() {
        try {
            if (reports.prepareScheduledReports(LocalDateTime.now(SystemPeriodicReportService.SHANGHAI))) {
                log.info("系统日/周/月报告按共享设置刷新完成");
            }
        } catch (RuntimeException exception) {
            log.error("系统日/周/月报告自动刷新失败，将在下次启动或调度时重试", exception);
        }
    }

    private void prepare(String trigger) {
        try {
            reports.prepareClosedPeriods();
            log.info("系统日/周/月报告{}完成", trigger);
        } catch (RuntimeException exception) {
            log.error("系统日/周/月报告{}失败，将在下次启动或调度时重试", trigger, exception);
        }
    }
}
