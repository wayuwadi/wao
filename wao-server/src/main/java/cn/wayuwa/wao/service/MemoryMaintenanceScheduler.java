package cn.wayuwa.wao.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 记忆派生事件补偿和每日完整性检查；不会改写权威正文。 */
@Component
public class MemoryMaintenanceScheduler {
    private static final Logger log = LoggerFactory.getLogger(MemoryMaintenanceScheduler.class);
    private final UnifiedMemoryService memories;

    public MemoryMaintenanceScheduler(UnifiedMemoryService memories) { this.memories = memories; }

    @Scheduled(fixedDelay = 300_000, initialDelay = 90_000)
    public void reconcileOutbox() {
        memories.pendingOutbox().forEach(row -> {
            try { memories.processOutbox(row); }
            catch (RuntimeException exception) { log.warn("Memory outbox retry failed: {}", row.getId(), exception); }
        });
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Shanghai")
    public void verifyIntegrity() {
        int invalid = memories.verifyPublishedPointers();
        if (invalid > 0) log.error("Unified memory integrity check found {} invalid published pointers", invalid);
    }
}
