package cn.wayuwa.wao.config;

import cn.wayuwa.wao.service.AttendanceSyncScheduler;
import cn.wayuwa.wao.service.RoutineReminderScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AttendanceSchedulingConfigurationTest {
    private final AttendanceSyncScheduler attendance = mock(AttendanceSyncScheduler.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfiguration.class, AttendanceSchedulingConfiguration.class)
            .withBean(AttendanceSyncScheduler.class, () -> attendance);

    @Test void sharedDatabaseDevelopmentDoesNotStartAutomaticSync() {
        context.withPropertyValues("wao.scheduling.enabled=false").run(app -> {
            assertThat(app).doesNotHaveBean("attendanceTaskScheduler")
                    .doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
            verifyNoInteractions(attendance);
        });
    }

    @Test void explicitOptInRepeatsAfterFailureWithoutEnablingOtherScheduledMethods() {
        doThrow(new IllegalStateException("temporary failure")).doNothing().when(attendance).syncToday();
        context.withPropertyValues("wao.scheduling.enabled=false", "wao.attendance.scheduling-enabled=true",
                "wao.attendance.initial-delay=PT0.05S", "wao.attendance.sync-delay=PT0.05S").run(app -> {
            assertThat(app).hasNotFailed().doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
            verify(attendance, timeout(3000).atLeast(2)).syncToday();
            assertThat(app.getBean("attendanceSyncSchedule", java.util.concurrent.ScheduledFuture.class).isDone()).isFalse();
        });
    }

    @Test void globalSchedulingDoesNotDoubleRegisterAttendance() {
        context.withPropertyValues("wao.attendance.scheduling-enabled=true").run(app -> {
            assertThat(app).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class)
                    .doesNotHaveBean("attendanceTaskScheduler");
        });
    }

    @Test void separateReminderAndAttendanceSchedulersCoexist() {
        context.withUserConfiguration(RoutineSchedulingConfiguration.class)
                .withBean(RoutineReminderScheduler.class, () -> mock(RoutineReminderScheduler.class))
                .withPropertyValues("wao.scheduling.enabled=false", "wao.attendance.scheduling-enabled=true",
                        "wao.routines.scheduling-enabled=true").run(app -> {
            assertThat(app).hasNotFailed().hasBean("attendanceTaskScheduler")
                    .hasBean("routineReminderTaskScheduler")
                    .doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
            assertThat(app.getBean("attendanceSyncSchedule", java.util.concurrent.ScheduledFuture.class)
                    .getDelay(java.util.concurrent.TimeUnit.SECONDS)).isBetween(0L, 10L);
        });
    }
}
