package cn.wayuwa.wao.service;

import cn.wayuwa.wao.mapper.NoteAiWorkflowMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 只推进已保存的小记 AI 任务，不依赖浏览器标签页存活。 */
@Component
public class NoteAiWorkflowScheduler {
    private static final Logger log = LoggerFactory.getLogger(NoteAiWorkflowScheduler.class);
    private final NoteAiWorkflowMapper mapper;
    private final NoteAiWorkflowService workflows;

    public NoteAiWorkflowScheduler(NoteAiWorkflowMapper mapper, NoteAiWorkflowService workflows) {
        this.mapper = mapper; this.workflows = workflows;
    }

    @Scheduled(fixedDelay = 20_000, initialDelay = 30_000)
    public void advance() {
        for (String docId : mapper.pendingDocIds(30)) {
            try { workflows.advance(docId); }
            catch (RuntimeException ex) {
                log.warn("小记自动整理推进失败：{}", docId, ex);
                try { workflows.failCurrent(docId, ex.getMessage()); }
                catch (RuntimeException saveError) { log.warn("小记自动整理失败状态保存失败：{}", docId, saveError); }
            }
        }
    }
}
