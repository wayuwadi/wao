package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.ChatReviewRunEntity;
import cn.wayuwa.wao.mapper.ChatReviewRunMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在同一事务内把复盘运行与新入队的只读 AI 运行绑定。 */
@Service
public class ChatReviewAiQueue {

    private final ChatReviewRunMapper reviews;
    private final AiAssistantService assistant;

    public ChatReviewAiQueue(ChatReviewRunMapper reviews, AiAssistantService assistant) {
        this.reviews = reviews;
        this.assistant = assistant;
    }

    /**
     * 非阻塞入队并持久化关联；两步任一失败都会整体回滚。
     *
     * @param reviewId 聊天复盘运行 id，同时作为只读任务的内部归属键
     * @param workerToken 当前数据库租约持有者
     * @param prompt 服务端已校验小于六万字的分析提示
     * @return 新建或已存在的 {@code ai_assistant_run.id}
     */
    @Transactional
    public String enqueue(String reviewId, String workerToken, String prompt) {
        ChatReviewRunEntity run = reviews.selectById(reviewId);
        if (run == null) throw new NotFoundException("聊天复盘运行不存在");
        String aiRunId = assistant.enqueueReadOnlyJob(reviewId, prompt);
        if (reviews.linkAiRun(reviewId, workerToken, aiRunId) != 1) {
            throw new ChatReviewLeaseLostException();
        }
        return aiRunId;
    }
}
