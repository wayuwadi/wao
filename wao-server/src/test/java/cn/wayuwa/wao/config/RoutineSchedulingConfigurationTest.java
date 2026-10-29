package cn.wayuwa.wao.config;

import cn.wayuwa.wao.service.RoutineReminderScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RoutineSchedulingConfigurationTest {
    private final RoutineReminderScheduler reminders = mock(RoutineReminderScheduler.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfiguration.class, RoutineSchedulingConfiguration.class)
            .withBean(RoutineReminderScheduler.class, () -> reminders);

    @Test void disabledLocalDeploymentDoesNotStartBackgroundWork() {
        context.withPropertyValues("wao.scheduling.enabled=false").run(app -> {
            assertThat(app).doesNotHaveBean("routineReminderTaskScheduler")
                    .doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
            verifyNoInteractions(reminders);
        });
    }

    @Test void explicitOptInRunsOnlyRoutineReminders() {
        context.withPropertyValues("wao.scheduling.enabled=false", "wao.routines.scheduling-enabled=true").run(app -> {
            assertThat(app).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
            var scheduler = app.getBean("routineReminderTaskScheduler", ThreadPoolTaskScheduler.class);
            assertThat(scheduler.getScheduledThreadPoolExecutor().getQueue()).hasSize(1);
            verify(reminders, timeout(15000)).run();
        });
    }

    @Test void normalDeploymentDoesNotDoubleSchedule() {
        context.withPropertyValues("wao.routines.scheduling-enabled=true").run(app -> {
            assertThat(app).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class)
                    .doesNotHaveBean("routineReminderTaskScheduler");
        });
    }

    @Test void independentCollectorsCoexist() {
        context.withUserConfiguration(LocalCodexQuotaSchedulingConfiguration.class)
                .withBean(cn.wayuwa.wao.service.LocalCodexQuotaService.class,
                        () -> mock(cn.wayuwa.wao.service.LocalCodexQuotaService.class))
                .withPropertyValues("wao.scheduling.enabled=false", "wao.routines.scheduling-enabled=true",
                        "wao.codex-quota.enabled=true").run(app -> {
            assertThat(app).hasNotFailed().doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
            assertThat(app.getBean("routineReminderTaskScheduler", ThreadPoolTaskScheduler.class)
                    .getScheduledThreadPoolExecutor().getQueue()).hasSize(1);
        });
    }
}
