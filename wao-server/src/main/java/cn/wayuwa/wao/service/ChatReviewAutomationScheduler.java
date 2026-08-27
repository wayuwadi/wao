package cn.wayuwa.wao.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每分钟检查自动聊天复盘；数据库计划点保证多 API 节点不会重复触发。 */
@Component
public class ChatReviewAutomationScheduler {
    private static final Logger log = LoggerFactory.getLogger(ChatReviewAutomationScheduler.class);
    private final ChatReviewAutomationService automation;

    public ChatReviewAutomationScheduler(ChatReviewAutomationService automation) {
        this.automation = automation;
    }

    @Scheduled(fixedDelayString = "${wao.chat-review.automation-poll-ms:60000}",
            initialDelayString = "${wao.chat-review.automation-initial-delay-ms:30000}")
    public void triggerDue() {
        try {
            automation.triggerDue();
        } catch (RuntimeException exception) {
            log.warn("自动聊天复盘本轮未能入队：{}", exception.getMessage());
        }
    }
}
