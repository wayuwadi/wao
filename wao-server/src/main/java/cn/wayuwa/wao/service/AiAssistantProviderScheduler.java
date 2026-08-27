package cn.wayuwa.wao.service;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 在 API 节点上消费 DeepSeek / 受信模型网关的只读任务。 */
@Component
public class AiAssistantProviderScheduler {
    private static final Logger log = LoggerFactory.getLogger(AiAssistantProviderScheduler.class);
    private final AiAssistantService assistant;
    private final AtomicBoolean running = new AtomicBoolean();

    public AiAssistantProviderScheduler(AiAssistantService assistant) {
        this.assistant = assistant;
    }

    /** 单节点内不并发出站；多节点由数据库 claim token 保证每条任务只被一个 attempt 持有。 */
    @Scheduled(fixedDelayString = "${wao.ai.provider.poll-interval-ms:1000}",
            initialDelayString = "${wao.ai.provider.initial-delay-ms:1500}")
    public void advance() {
        if (!running.compareAndSet(false, true)) return;
        try {
            assistant.expireProviderJobs();
            assistant.processNextProviderRun();
        } catch (RuntimeException exception) {
            log.error("服务端 AI Provider 队列执行异常", exception);
        } finally {
            running.set(false);
        }
    }
}
