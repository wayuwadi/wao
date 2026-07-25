package cn.wayuwa.wao.service;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 回收站清理调度器：每小时物理删除一次过了保留期（{@link KnowledgeService#TRASH_DAYS} 天）
 * 的知识库 / 文件夹 / 文档，同时清掉早已过期的协同在场记录。
 *
 * <p>两个节点会各跑各的，但清理是幂等的（按 {@code deleted_at < cutoff} 删），重复执行无害。
 */
@Component
public class TrashPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(TrashPurgeScheduler.class);

    private final KnowledgeService knowledge;
    private final DocPresenceService presence;

    public TrashPurgeScheduler(KnowledgeService knowledge, DocPresenceService presence) {
        this.knowledge = knowledge;
        this.presence = presence;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
    public void purgeExpired() {
        LocalDateTime now = LocalDateTime.now();
        try {
            int removed = knowledge.purgeExpired(now);
            if (removed > 0) {
                log.info("回收站清理：彻底删除 {} 个过期条目", removed);
            }
            presence.sweep(now);
        } catch (Exception ex) {
            log.error("回收站清理失败", ex);
        }
    }
}
