package cn.wayuwa.wao.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

class LifeReviewSchedulerTest {
    @Test
    void doesNotQueueAnotherLongBatchWhilePreviousOneIsRunning() {
        LifeReviewService service = mock(LifeReviewService.class);
        when(service.prepareClosedPeriods()).thenReturn(
                new LifeReviewService.BatchResult(2, 0, 6, 0, null));
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        TaskExecutor executor = submitted::set;
        LifeReviewScheduler scheduler = new LifeReviewScheduler(service, executor);

        scheduler.prepareClosedPeriods();
        scheduler.prepareClosedPeriods();
        verify(service, times(0)).prepareClosedPeriods();

        submitted.get().run();
        verify(service, times(1)).prepareClosedPeriods();
    }
}
