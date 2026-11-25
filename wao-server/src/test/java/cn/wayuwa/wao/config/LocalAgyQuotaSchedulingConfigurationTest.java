package cn.wayuwa.wao.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import cn.wayuwa.wao.service.AgyQuotaService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class LocalAgyQuotaSchedulingConfigurationTest {
    private final AgyQuotaService quotas = mock(AgyQuotaService.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfiguration.class, LocalAgyQuotaSchedulingConfiguration.class)
            .withBean(AgyQuotaService.class, () -> quotas);

    @Test
    void disabledCollectorDoesNotRegisterBackgroundWork() {
        context.withPropertyValues("wao.scheduling.enabled=false").run(app -> {
            assertThat(app).doesNotHaveBean("localAgyQuotaTaskScheduler")
                    .doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
            verifyNoInteractions(quotas);
        });
    }

    @Test
    void explicitCollectorRunsWithGlobalSchedulingOff() {
        context.withPropertyValues("wao.scheduling.enabled=false", "wao.agy-quota.enabled=true").run(app -> {
            assertThat(app).hasSingleBean(ThreadPoolTaskScheduler.class)
                    .doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
            var executor = app.getBean("localAgyQuotaTaskScheduler", ThreadPoolTaskScheduler.class)
                    .getScheduledThreadPoolExecutor();
            assertThat(executor.getCorePoolSize()).isEqualTo(1);
            assertThat(executor.getQueue()).hasSize(1);
        });
    }

    @Test
    void normalDeploymentUsesOnlyExistingAnnotationScheduler() {
        context.withPropertyValues("wao.agy-quota.enabled=true").run(app -> {
            assertThat(app).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class)
                    .doesNotHaveBean("localAgyQuotaTaskScheduler")
                    .doesNotHaveBean("localAgyQuotaSchedule");
        });
    }
}
