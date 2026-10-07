package cn.wayuwa.wao.service;

import cn.wayuwa.wao.mapper.FeishuBotInboxMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 持久回调异步处理；重启后继续，失败最多尝试六次并留下明确结果。 */
@Component
public class FeishuBotInboxWorker {
    private static final Logger LOG = LoggerFactory.getLogger(FeishuBotInboxWorker.class);
    private final FeishuBotInboxMapper inbox;
    private final FeishuQuickCollectService collect;
    public FeishuBotInboxWorker(FeishuBotInboxMapper inbox, FeishuQuickCollectService collect) {
        this.inbox=inbox; this.collect=collect;
    }
    /** 每秒处理最多十条已认领的回调，避免把飞书三秒应答窗口用于网络与内容创建。 */
    @Scheduled(fixedDelay = 1000, initialDelay = 10000)
    public void drain() {
        inbox.expireExhausted();
        for (var row : inbox.pending()) {
            String lease = java.util.UUID.randomUUID().toString().replace("-", "");
            if (inbox.claim(row.getId(), lease) != 1) continue;
            row.setLeaseToken(lease);
            try { collect.processInbox(row); }
            catch (RuntimeException ex) { inbox.retry(row.getId(), lease); LOG.warn("飞书消息收集暂未完成，已保留重试结果"); }
        }
    }
    /** 清理已完成的加密空载回调，业务收集记录独立保留。 */
    @Scheduled(cron = "0 20 4 * * *")
    public void purge() { inbox.purgeCompleted(); }
}
