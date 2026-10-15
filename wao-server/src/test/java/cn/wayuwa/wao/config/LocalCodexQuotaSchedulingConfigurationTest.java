package cn.wayuwa.wao.config;

import cn.wayuwa.wao.service.LocalCodexQuotaService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class LocalCodexQuotaSchedulingConfigurationTest {
    private final LocalCodexQuotaService quotas = mock(LocalCodexQuotaService.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfiguration.class, LocalCodexQuotaSchedulingConfiguration.class)
            .withBean(LocalCodexQuotaService.class, () -> quotas);

    @Test void disabledCollectorDoesNotRegisterBackgroundWork() {
        context.withPropertyValues("wao.scheduling.enabled=false").run(app -> {
            assertThat(app).doesNotHaveBean(ThreadPoolTaskScheduler.class)
                    .doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
            verifyNoInteractions(quotas);
        });
    }

    @Test void explicitCollectorRunsWithGlobalSchedulingOff() {
        context.withPropertyValues("wao.scheduling.enabled=false", "wao.codex-quota.enabled=true").run(app -> {
            assertThat(app).hasSingleBean(ThreadPoolTaskScheduler.class)
                    .doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
            var executor = app.getBean(ThreadPoolTaskScheduler.class).getScheduledThreadPoolExecutor();
            assertThat(executor.getCorePoolSize()).isEqualTo(1);
            assertThat(executor.getQueue()).hasSize(1);
            verify(quotas, timeout(15000)).refresh();
        });
    }

    @Test void normalDeploymentUsesOnlyExistingAnnotationScheduler() {
        context.withPropertyValues("wao.codex-quota.enabled=true").run(app -> {
            assertThat(app).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class)
                    .doesNotHaveBean("localCodexQuotaTaskScheduler")
                    .doesNotHaveBean("localCodexQuotaSchedule");
        });
    }

    @Test void explicitDisableOverridesLocalOptIn() {
        context.withPropertyValues("wao.scheduling.enabled=false", "wao.codex-quota.enabled=false").run(app -> {
            assertThat(app).doesNotHaveBean("localCodexQuotaTaskScheduler");
            verifyNoInteractions(quotas);
        });
    }

    @Test void quotaAndFeishuSchedulersCoexistWithoutEnablingOtherJobs() {
        context.withUserConfiguration(FeishuCollectionSchedulingConfiguration.class)
                .withBean(cn.wayuwa.wao.service.FeishuDailyArchiveService.class,
                        () -> mock(cn.wayuwa.wao.service.FeishuDailyArchiveService.class))
                .withBean(cn.wayuwa.wao.service.FeishuBotInboxWorker.class,
                        () -> mock(cn.wayuwa.wao.service.FeishuBotInboxWorker.class))
                .withPropertyValues("wao.scheduling.enabled=false", "wao.codex-quota.enabled=true",
                        "wao.feishu.collection-scheduling-enabled=true").run(app -> {
                    assertThat(app).hasNotFailed().doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
                    assertThat(app.getBean("localCodexQuotaTaskScheduler", ThreadPoolTaskScheduler.class)
                            .getScheduledThreadPoolExecutor().getQueue()).hasSize(1);
                    assertThat(app.getBean("feishuCollectionTaskScheduler", ThreadPoolTaskScheduler.class)
                            .getScheduledThreadPoolExecutor().getQueue()).hasSize(3);
                });
    }
}
