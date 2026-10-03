package cn.wayuwa.wao.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 可恢复的后台推进；数据库租约允许与本机 worker 安全重叠。 */
@Component
public class NoteDiscoveryScheduler {
    private final NoteDiscoveryService service;
    public NoteDiscoveryScheduler(NoteDiscoveryService service) { this.service=service; }
    @Scheduled(fixedDelay=20_000,initialDelay=20_000)
    public void tick() { service.tick(); }
    @Scheduled(fixedDelay=3_600_000,initialDelay=3_600_000)
    public void cleanup() { service.cleanup(); }
}
