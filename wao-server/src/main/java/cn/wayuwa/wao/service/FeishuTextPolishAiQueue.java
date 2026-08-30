package cn.wayuwa.wao.service;

import cn.wayuwa.wao.domain.FeishuTextPolishRunEntity;
import cn.wayuwa.wao.mapper.FeishuTextPolishRunMapper;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在同一事务内认领飞书润色请求、创建只读 AI 运行并持久化关联。 */
@Service
public class FeishuTextPolishAiQueue {
    private final FeishuTextPolishRunMapper runs;
    private final AiAssistantService assistant;

    public FeishuTextPolishAiQueue(FeishuTextPolishRunMapper runs, AiAssistantService assistant) {
        this.runs = runs;
        this.assistant = assistant;
    }

    /** 多节点竞争时只有一个事务能创建并绑定 AI 运行。 */
    @Transactional
    public void enqueue(String runId) {
        LocalDateTime now = LocalDateTime.now();
        if (runs.claimQueued(runId, now) != 1) return;
        FeishuTextPolishRunEntity run = runs.selectById(runId);
        String aiRunId = assistant.enqueueTextPolishJob(
                run.getId(), run.getOriginalText(), run.getPromptSnapshot());
        if (runs.linkAiRun(runId, aiRunId, LocalDateTime.now()) != 1) {
            throw new IllegalStateException("飞书润色 AI 运行关联失败");
        }
    }
}
