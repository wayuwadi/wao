package cn.wayuwa.wao.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

class DailyGuidanceWarmupSchedulerTest {

    @Test
    void longRunningBatchDoesNotQueueAnotherWarmup() {
        DailyGuidanceService guidance = mock(DailyGuidanceService.class);
        when(guidance.prepareMissingForAllMembers())
                .thenReturn(new DailyGuidanceService.WarmupResult(2, 2, 0, 0, null));
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        TaskExecutor executor = submitted::set;
        DailyGuidanceWarmupScheduler scheduler = new DailyGuidanceWarmupScheduler(guidance, executor);

        scheduler.prepareMissing();
        scheduler.prepareMissing();
        verify(guidance, times(0)).prepareMissingForAllMembers();

        submitted.get().run();
        verify(guidance, times(1)).prepareMissingForAllMembers();
    }
}
