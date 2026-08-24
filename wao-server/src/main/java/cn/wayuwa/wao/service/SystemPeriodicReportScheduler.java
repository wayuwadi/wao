package cn.wayuwa.wao.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 只在指定节点于上海午夜产出系统日报，并按边界同时产出周报、月报。 */
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

    /** 每天上海时间 00:00 产出最近结束的周期；幂等键允许安全重试。 */
    @Scheduled(cron = "${wao.system-report.cron:0 0 0 * * *}", zone = "Asia/Shanghai")
    public void prepareAtMidnight() {
        try {
            reports.prepareMidnightReports();
            log.info("系统日/周/月报告午夜产出完成");
        } catch (RuntimeException exception) {
            log.error("系统日/周/月报告午夜产出失败，将在下次启动或调度时重试", exception);
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
