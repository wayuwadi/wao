package cn.wayuwa.wao.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import cn.wayuwa.wao.domain.AutomationJobEntity;
import org.junit.jupiter.api.Test;

class JobSchedulerServiceTest {

    private final AutomationService automation = mock(AutomationService.class);
    private final NetworkOutageWindowService networkOutageWindow = mock(NetworkOutageWindowService.class);
    private final JobSchedulerService scheduler = new JobSchedulerService(automation, networkOutageWindow);

    @Test
    void outageWindowLeavesDueJobsUnclaimedForRecovery() {
        when(networkOutageWindow.isUnavailable()).thenReturn(true);

        scheduler.triggerDueJobs();

        verify(automation, never()).dueJobs(any());
    }

    @Test
    void availableWindowExecutesEveryDueJob() {
        AutomationJobEntity job = new AutomationJobEntity();
        when(automation.dueJobs(any())).thenReturn(List.of(job));

        scheduler.triggerDueJobs();

        verify(automation).executeScheduled(job);
    }
}
