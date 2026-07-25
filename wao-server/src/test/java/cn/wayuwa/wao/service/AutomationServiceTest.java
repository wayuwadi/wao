package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.AutomationJobEntity;
import cn.wayuwa.wao.dto.AutomationJobDto;
import cn.wayuwa.wao.dto.CreateJobRequest;
import cn.wayuwa.wao.mapper.AutomationJobMapper;
import cn.wayuwa.wao.mapper.AutomationRunMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AutomationServiceTest {

    private final AutomationJobMapper jobMapper = mock(AutomationJobMapper.class);
    private final AutomationRunMapper runMapper = mock(AutomationRunMapper.class);
    private final AutomationService service = new AutomationService(jobMapper, runMapper, new ObjectMapper());

    private AutomationJobEntity job(String status) {
        AutomationJobEntity e = new AutomationJobEntity();
        e.setId("j1");
        e.setName("测试任务");
        e.setStatus(status);
        e.setStepsJson("[\"步骤一\",\"步骤二\"]");
        return e;
    }

    @Test
    void createJobDefaultsToNormalStatusAndNoCron() {
        when(jobMapper.selectCount(null)).thenReturn(0L);

        AutomationJobDto dto = service.createJob(
                new CreateJobRequest("新任务", null, null, "描述", "来源", "目标", List.of("步骤一"), null));

        assertThat(dto.id()).startsWith("j");
        assertThat(dto.status()).isEqualTo("正常");
        assertThat(dto.last()).isEqualTo("尚未运行");
    }

    @Test
    void createJobRejectsInvalidCron() {
        when(jobMapper.selectCount(null)).thenReturn(0L);

        assertThatThrownBy(() -> service.createJob(
                new CreateJobRequest("新任务", null, null, null, null, null, List.of(), "not-a-cron")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runJobOnBrokenStatusFailsAndKeepsStatus() {
        AutomationJobEntity e = job("待修复");
        when(jobMapper.selectById("j1")).thenReturn(e);
        when(runMapper.selectList(any())).thenReturn(List.of());

        AutomationJobDto dto = service.runJob("j1");

        assertThat(dto.status()).isEqualTo("待修复");
        assertThat(dto.last()).contains("失败");
    }

    @Test
    void runJobOnHealthyStatusSucceeds() {
        AutomationJobEntity e = job("正常");
        when(jobMapper.selectById("j1")).thenReturn(e);
        when(runMapper.selectList(any())).thenReturn(List.of());

        AutomationJobDto dto = service.runJob("j1");

        assertThat(dto.status()).isEqualTo("正常");
        assertThat(dto.last()).contains("成功");
    }

    @Test
    void pauseThenResumeRestoresNormalStatus() {
        AutomationJobEntity e = job("正常");
        when(jobMapper.selectById("j1")).thenReturn(e);
        when(runMapper.selectList(any())).thenReturn(List.of());

        service.pauseJob("j1");
        assertThat(e.getStatus()).isEqualTo("已暂停");

        AutomationJobDto resumed = service.resumeJob("j1");
        assertThat(resumed.status()).isEqualTo("正常");
    }

    @Test
    void reauthRestoresNormalStatus() {
        AutomationJobEntity e = job("待修复");
        when(jobMapper.selectById("j1")).thenReturn(e);
        when(runMapper.selectList(any())).thenReturn(List.of());

        AutomationJobDto dto = service.reauthJob("j1");

        assertThat(dto.status()).isEqualTo("正常");
    }

    @Test
    void getJobThrowsWhenMissing() {
        when(jobMapper.selectById("nope")).thenReturn(null);
        assertThatThrownBy(() -> service.getJob("nope")).isInstanceOf(NotFoundException.class);
    }
}
