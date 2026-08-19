package cn.wayuwa.wao.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 独立于聊天复盘开关的只读 AI 超时与提示材料清理。 */
@Component
@ConditionalOnProperty(name = "wao.ai.read-only.cleanup-enabled", havingValue = "true", matchIfMissing = true)
public class AiAssistantTimeoutScheduler {

    private final AiAssistantService assistant;

    public AiAssistantTimeoutScheduler(AiAssistantService assistant) {
        this.assistant = assistant;
    }

    /** 每分钟由任意 API 节点幂等清理超时的内部只读任务。 */
    @Scheduled(fixedDelayString = "${wao.ai.read-only.cleanup-interval-ms:60000}",
            initialDelayString = "${wao.ai.read-only.cleanup-initial-delay-ms:60000}")
    public void expire() {
        assistant.expireReadOnlyJobs();
    }
}
