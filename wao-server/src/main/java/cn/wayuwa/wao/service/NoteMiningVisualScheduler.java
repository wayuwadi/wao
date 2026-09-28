package cn.wayuwa.wao.service;

import cn.wayuwa.wao.mapper.NoteMiningVisualMapper;
import cn.wayuwa.wao.mapper.NoteMiningMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 已物化的小记启发图在服务端逐张续跑，不依赖浏览器保持打开。 */
@Component
public class NoteMiningVisualScheduler {
    private static final Logger log = LoggerFactory.getLogger(NoteMiningVisualScheduler.class);
    private final NoteMiningVisualMapper visuals;
    private final NoteMiningMapper mining;
    private final NoteMiningService service;

    public NoteMiningVisualScheduler(NoteMiningVisualMapper visuals, NoteMiningMapper mining, NoteMiningService service) {
        this.visuals = visuals;
        this.mining = mining;
        this.service = service;
    }

    @Scheduled(fixedDelay = 20_000L, initialDelay = 20_000L)
    public void advance() {
        for (NoteMiningMapper.Job job : mining.pendingJobs(30)) {
            try { service.get(job.memberId(), job.id()); }
            catch (RuntimeException ex) { log.warn("小记挖掘结果物化失败：{}", job.id(), ex); }
        }
        for (NoteMiningVisualMapper.Key key : visuals.pending(30)) {
            try { service.advanceVisual(key.jobId(), key.lens()); }
            catch (RuntimeException ex) { log.warn("小记启发图推进失败：{} / {}", key.jobId(), key.lens(), ex); }
        }
    }
}
