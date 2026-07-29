package cn.wayuwa.wao.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * CalDAV 定时同步：每 15 分钟拉取一次所有账户的订阅日历事件。
 *
 * <p>两个节点会各跑各的，但同步按日历「先清后灌」整体重建，重复执行幂等无害。
 */
@Component
public class CaldavSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(CaldavSyncScheduler.class);

    private final CaldavService caldavService;

    public CaldavSyncScheduler(CaldavService caldavService) {
        this.caldavService = caldavService;
    }

    @Scheduled(fixedDelay = 900_000, initialDelay = 60_000)
    public void syncAll() {
        try {
            caldavService.syncAll();
        } catch (Exception ex) {
            log.error("CalDAV 定时同步失败", ex);
        }
    }
}
