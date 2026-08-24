package cn.wayuwa.wao.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class SystemPeriodicReportSchedulerTest {
    @Test
    void startupBackfillsMissingPeriodsAndMidnightUsesBoundarySchedule() {
        SystemPeriodicReportService service = mock(SystemPeriodicReportService.class);
        SystemPeriodicReportScheduler scheduler = new SystemPeriodicReportScheduler(service);

        scheduler.prepareAfterStartup();
        scheduler.prepareAtMidnight();

        verify(service).prepareClosedPeriods();
        verify(service).prepareMidnightReports();
    }
}
