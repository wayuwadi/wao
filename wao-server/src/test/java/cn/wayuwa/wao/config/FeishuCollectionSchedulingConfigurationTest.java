package cn.wayuwa.wao.config;

import cn.wayuwa.wao.service.FeishuBotInboxWorker;
import cn.wayuwa.wao.service.FeishuDailyArchiveService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class FeishuCollectionSchedulingConfigurationTest {
    private final FeishuDailyArchiveService archive = mock(FeishuDailyArchiveService.class);
    private final FeishuBotInboxWorker inbox = mock(FeishuBotInboxWorker.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfiguration.class, FeishuCollectionSchedulingConfiguration.class)
            .withBean(FeishuDailyArchiveService.class, () -> archive)
            .withBean(FeishuBotInboxWorker.class, () -> inbox);

    @Test
    void localCloudRemainsQuietWithoutExplicitDeploymentOptIn() {
        context.withPropertyValues("wao.scheduling.enabled=false").run(app -> {
            assertThat(app).doesNotHaveBean(ThreadPoolTaskScheduler.class)
                    .doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
            verifyNoInteractions(archive, inbox);
        });
    }

    @Test
    void deploymentOptInRegistersOnlyThreeFeishuJobsWithoutGlobalScheduling() {
        context.withPropertyValues("wao.scheduling.enabled=false", "wao.feishu.collection-scheduling-enabled=true")
                .run(app -> {
                    assertThat(app).hasSingleBean(ThreadPoolTaskScheduler.class)
                            .doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
                    var scheduler = app.getBean(ThreadPoolTaskScheduler.class);
                    assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(2);
                    assertThat(scheduler.getScheduledThreadPoolExecutor().getQueue()).hasSize(3);
                    verifyNoInteractions(archive, inbox);
                });
    }

    @Test
    void normalDeploymentDoesNotDoubleRegisterFeishuJobs() {
        context.withPropertyValues("wao.feishu.collection-scheduling-enabled=true").run(app -> {
            assertThat(app).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class)
                    .doesNotHaveBean("feishuCollectionTaskScheduler")
                    .doesNotHaveBean("feishuCollectionSchedules");
        });
    }

    @Test
    void obsoletePollingPropertyRegistersNoPollingTask() {
        context.withPropertyValues("wao.scheduling.enabled=false", "wao.feishu.collection-scheduling-enabled=true",
                "wao.feishu.quick-collect-poll-ms=0").run(app -> {
                    assertThat(app).hasNotFailed();
                    assertThat(app.getBean(ThreadPoolTaskScheduler.class).getScheduledThreadPoolExecutor().getQueue()).hasSize(3);
                    assertThat(app).doesNotHaveBean("feishuQuickCollectPollingScheduler");
                    verifyNoInteractions(archive, inbox);
                });
    }
}
