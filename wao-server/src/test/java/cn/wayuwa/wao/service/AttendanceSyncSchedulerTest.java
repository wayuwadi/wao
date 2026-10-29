package cn.wayuwa.wao.service;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class AttendanceSyncSchedulerTest {
    @Test void temporaryMemberFailureDoesNotBlockOtherMembersOrNextCycle() {
        var service = mock(AttendanceService.class);
        var scheduler = new AttendanceSyncScheduler(service);
        var today = LocalDate.now(AttendanceService.ZONE);
        when(service.enabledMembers()).thenReturn(List.of("member-a", "member-b"));
        when(service.sync("member-a", today, today)).thenThrow(new MokaAttendanceClient.RemoteException("temporary")).thenReturn(null);
        scheduler.syncToday();
        verify(service).sync("member-b", today, today);
        verify(service, never()).backfillNextPeriod("member-a", today);
        scheduler.syncToday();
        verify(service, times(2)).sync("member-a", today, today);
        verify(service).backfillNextPeriod("member-a", today);
        verify(service, times(2)).backfillNextPeriod("member-b", today);
        verify(service, never()).sync(any(), any(), any(), eq(true));
    }

    @Test void databaseScanFailureDoesNotStopTheNextCycle() {
        var service = mock(AttendanceService.class);
        var scheduler = new AttendanceSyncScheduler(service);
        var today = LocalDate.now(AttendanceService.ZONE);
        when(service.enabledMembers()).thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(List.of("member-a"));
        scheduler.syncToday();
        scheduler.syncToday();
        verify(service).sync("member-a", today, today);
        verify(service).backfillNextPeriod("member-a", today);
    }
}
