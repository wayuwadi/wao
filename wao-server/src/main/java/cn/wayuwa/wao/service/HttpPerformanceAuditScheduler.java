package cn.wayuwa.wao.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 只在指定部署节点启用的夜间 HTTP 性能巡检触发器。 */
@Component
@ConditionalOnProperty(name = "wao.http-performance.scheduler-enabled", havingValue = "true")
public class HttpPerformanceAuditScheduler {

    private final HttpPerformanceAuditService audits;

    public HttpPerformanceAuditScheduler(HttpPerformanceAuditService audits) {
        this.audits = audits;
    }

    /** 每晚按上海时区触发；具体检查、去重和派发由服务完成。 */
    @Scheduled(cron = "${wao.http-performance.cron:0 30 23 * * *}", zone = "Asia/Shanghai")
    public void runNightly() {
        audits.runNightlyAudit();
    }
}
