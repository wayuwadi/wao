package cn.wayuwa.wao.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import cn.wayuwa.wao.service.TianrenSharedDailyService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

class TianrenSharedDailySchedulingConfigurationTest {
    private final TianrenSharedDailyService daily = mock(TianrenSharedDailyService.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfiguration.class, TianrenSharedDailySchedulingConfiguration.class)
            .withBean(TianrenSharedDailyService.class, () -> daily);

    @Test
    void sharedDatabaseDevelopmentLeavesTheCastDisabled() {
        context.withPropertyValues("wao.scheduling.enabled=false").run(app -> {
            assertThat(app).doesNotHaveBean("tianrenSharedDailyTaskScheduler")
                    .doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
            verifyNoInteractions(daily);
        });
    }

    @Test
    void productionOptInRunsOnlyTheSharedCastAndRetriesFailures() {
        doThrow(new IllegalStateException("temporary failure")).doNothing().when(daily).generateDue();
        context.withPropertyValues("wao.scheduling.enabled=false", "wao.tianren.shared-daily-scheduler-enabled=true",
                "wao.tianren.shared-daily-initial-delay=PT0.05S", "wao.tianren.shared-daily-delay=PT0.05S")
                .run(app -> {
                    assertThat(app).hasNotFailed().doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
                    verify(daily, timeout(3000).atLeast(2)).generateDue();
                    assertThat(app.getBean("tianrenSharedDailySchedule", java.util.concurrent.ScheduledFuture.class).isDone())
                            .isFalse();
                });
    }

    @Test
    void globalSchedulingDoesNotRegisterASecondCast() {
        context.withPropertyValues("wao.tianren.shared-daily-scheduler-enabled=true").run(app -> {
            assertThat(app).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class)
                    .doesNotHaveBean("tianrenSharedDailyTaskScheduler");
        });
    }
}
