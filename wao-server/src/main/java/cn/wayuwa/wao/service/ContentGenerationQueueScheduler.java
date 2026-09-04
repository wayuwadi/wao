package cn.wayuwa.wao.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 在主服务内推进需要 WAO 推荐模型凭据的自媒体成稿任务。 */
@Component
public class ContentGenerationQueueScheduler {
    private static final Logger log = LoggerFactory.getLogger(ContentGenerationQueueScheduler.class);
    private final ObjectProvider<JdbcTemplate> jdbc;
    private final ObjectProvider<AiAccountService> accounts;
    private final ObjectProvider<DeepSeekApiClient> model;
    private final ObjectMapper json;
    private final AtomicBoolean running = new AtomicBoolean();

    public ContentGenerationQueueScheduler(ObjectProvider<JdbcTemplate> jdbc,
                                           ObjectProvider<AiAccountService> accounts,
                                           ObjectProvider<DeepSeekApiClient> model, ObjectMapper json) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.model = model;
        this.json = json;
    }

    /** 本节点串行执行，多节点由数据库 claim token 和租约保证互斥与崩溃恢复。 */
    @Scheduled(fixedDelayString = "${wao.ai.content-generation.poll-interval-ms:1000}",
            initialDelayString = "${wao.ai.content-generation.initial-delay-ms:5000}")
    public void advance() {
        if (!running.compareAndSet(false, true)) return;
        try {
            JdbcTemplate template = jdbc.getIfAvailable();
            AiAccountService accountService = accounts.getIfAvailable();
            DeepSeekApiClient api = model.getIfAvailable();
            if (template == null || accountService == null || api == null) return;
            new ContentGenerationQueueProcessor(template, accountService, api, json).advance();
        } catch (BadSqlGrammarException exception) {
            log.debug("自媒体成稿队列表尚未就绪");
        } catch (RuntimeException exception) {
            log.error("自媒体成稿队列执行异常", exception);
        } finally {
            running.set(false);
        }
    }
}
