package cn.wayuwa.wao.service;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 自动化任务轮询调度器：每分钟检查一次到期的 Cron 任务并触发执行。 */
@Component
public class JobSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(JobSchedulerService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final AutomationService automationService;
    private final NetworkOutageWindowService networkOutageWindow;

    public JobSchedulerService(AutomationService automationService,
            NetworkOutageWindowService networkOutageWindow) {
        this.automationService = automationService;
        this.networkOutageWindow = networkOutageWindow;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void triggerDueJobs() {
        if (networkOutageWindow.isUnavailable()) {
            log.debug("当前处于系统断网时段，自动化任务保留原计划点并在恢复后重试");
            return;
        }
        automationService.dueJobs(LocalDateTime.now(ZONE)).forEach(job -> {
            try {
                automationService.executeScheduled(job);
            } catch (Exception ex) {
                log.error("自动化任务执行失败：{}", job.getId(), ex);
            }
        });
    }
}
