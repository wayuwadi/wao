package cn.wayuwa.wao.service;

import cn.wayuwa.wao.domain.FeishuTextPolishRunEntity;
import cn.wayuwa.wao.mapper.FeishuTextPolishRunMapper;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 发送成功后把结果幂等写入成员润色历史，并收口飞书运行。 */
@Service
public class FeishuTextPolishCompletionService {
    private final FeishuTextPolishRunMapper runs;
    private final TextPolishService textPolish;

    public FeishuTextPolishCompletionService(FeishuTextPolishRunMapper runs,
            TextPolishService textPolish) {
        this.runs = runs;
        this.textPolish = textPolish;
    }

    /** 同一事务只产生一条稳定 id 的历史记录。 */
    @Transactional
    public boolean complete(String runId) {
        LocalDateTime now = LocalDateTime.now();
        if (runs.claimDelivered(runId, now) != 1) return false;
        FeishuTextPolishRunEntity run = runs.selectById(runId);
        textPolish.recordResult(run.getRecordId(), run.getMemberId(), run.getPromptSnapshot(),
                run.getOriginalText(), run.getPolishedText());
        if (runs.markSucceeded(runId, LocalDateTime.now()) != 1) {
            throw new IllegalStateException("飞书润色历史状态收口失败");
        }
        return true;
    }
}
