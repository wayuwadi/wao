package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.AutomationJobEntity;
import cn.wayuwa.wao.dto.SaveHttpPerformanceInspectionConfigRequest;
import cn.wayuwa.wao.mapper.AutomationJobMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HttpPerformanceInspectionConfigServiceTest {

    private final SettingService settings = mock(SettingService.class);
    private final AutomationJobMapper jobs = mock(AutomationJobMapper.class);
    private final FeishuWebhookConfigService webhooks = mock(FeishuWebhookConfigService.class);
    private final HttpPerformanceInspectionConfigService service =
            new HttpPerformanceInspectionConfigService(settings, jobs, webhooks);
    private final AutomationJobEntity job = new AutomationJobEntity();

    @BeforeEach
    void defaults() {
        job.setId(HttpPerformanceInspectionConfigService.JOB_ID);
        job.setStatus("正常");
        when(jobs.selectById(HttpPerformanceInspectionConfigService.JOB_ID)).thenReturn(job);
        when(settings.get(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
        when(settings.get(anyString())).thenReturn(null);
    }

    @Test
    void savesUserFriendlyFrequencyAsARealSpringCronSchedule() {
        var request = new SaveHttpPerformanceInspectionConfigRequest(
                true, "WEEKDAYS", "07:45", 48, 8, 3, null);

        service.save(request);

        ArgumentCaptor<AutomationJobEntity> saved = ArgumentCaptor.forClass(AutomationJobEntity.class);
        verify(jobs).updateById(saved.capture());
        assertThat(saved.getValue().getCronExpr()).isEqualTo("0 45 7 * * MON-FRI");
        assertThat(saved.getValue().getScheduleLabel()).isEqualTo("工作日 07:45");
        assertThat(saved.getValue().getNextFireAt()).isNotNull();
        verify(settings).put(SettingService.HTTP_INSPECTION_TOP_N, "8");
        verify(settings).put(SettingService.HTTP_INSPECTION_MAX_ENDPOINTS, "3");
    }

    @Test
    void defaultsToDailyTopTenWhenNoCustomSettingsExist() {
        var config = service.get();

        assertThat(config.enabled()).isTrue();
        assertThat(config.frequency()).isEqualTo("DAILY");
        assertThat(config.time()).isEqualTo("06:30");
        assertThat(config.topN()).isEqualTo(10);
        assertThat(config.maxEndpointsPerRun()).isEqualTo(10);
    }

    @Test
    void rejectsABatchThatExceedsTheCandidatePool() {
        var request = new SaveHttpPerformanceInspectionConfigRequest(
                true, "DAILY", "06:30", 24, 3, 4, null);

        assertThatThrownBy(() -> service.save(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("不能超过");
    }
}
