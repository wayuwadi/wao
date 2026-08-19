package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.domain.ChatReviewRunEntity;
import cn.wayuwa.wao.mapper.ChatReviewRunMapper;
import org.junit.jupiter.api.Test;

class ChatReviewAiQueueTest {

    private final ChatReviewRunMapper reviews = mock(ChatReviewRunMapper.class);
    private final AiAssistantService assistant = mock(AiAssistantService.class);
    private final ChatReviewAiQueue queue = new ChatReviewAiQueue(reviews, assistant);

    @Test
    void linksQueuedAiRunOnlyThroughTheCurrentWorkerLeaseFence() {
        ChatReviewRunEntity review = new ChatReviewRunEntity();
        review.setId("cr1");
        when(reviews.selectById("cr1")).thenReturn(review);
        when(assistant.enqueueReadOnlyJob("cr1", "prompt")).thenReturn("ac1");
        when(reviews.linkAiRun(org.mockito.ArgumentMatchers.eq("cr1"),
                org.mockito.ArgumentMatchers.eq("worker-1"),
                org.mockito.ArgumentMatchers.eq("ac1"))).thenReturn(1);

        assertThat(queue.enqueue("cr1", "worker-1", "prompt")).isEqualTo("ac1");

        verify(assistant).enqueueReadOnlyJob("cr1", "prompt");
        verify(reviews).linkAiRun(org.mockito.ArgumentMatchers.eq("cr1"),
                org.mockito.ArgumentMatchers.eq("worker-1"),
                org.mockito.ArgumentMatchers.eq("ac1"));
    }

    @Test
    void rejectsBindingWhenLeaseWasLostSoTransactionCanRollBackAiInsert() {
        ChatReviewRunEntity review = new ChatReviewRunEntity();
        review.setId("cr1");
        when(reviews.selectById("cr1")).thenReturn(review);
        when(assistant.enqueueReadOnlyJob("cr1", "prompt")).thenReturn("ac1");
        when(reviews.linkAiRun(org.mockito.ArgumentMatchers.eq("cr1"),
                org.mockito.ArgumentMatchers.eq("stale-worker"),
                org.mockito.ArgumentMatchers.eq("ac1"))).thenReturn(0);

        assertThatThrownBy(() -> queue.enqueue("cr1", "stale-worker", "prompt"))
                .isInstanceOf(ChatReviewLeaseLostException.class)
                .hasMessageContaining("执行权已转移");
    }
}
