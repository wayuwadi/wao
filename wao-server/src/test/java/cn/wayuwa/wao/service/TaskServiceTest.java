package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.CreateTaskRequest;
import cn.wayuwa.wao.dto.PatchTaskRequest;
import cn.wayuwa.wao.dto.TaskDto;
import cn.wayuwa.wao.mapper.TaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class TaskServiceTest {

    private final TaskMapper mapper = mock(TaskMapper.class);
    private final TaskService service = new TaskService(mapper, new ObjectMapper());

    private TaskEntity todo() {
        TaskEntity e = new TaskEntity();
        e.setId("t1");
        e.setTitle("设计任务详情抽屉交互稿");
        e.setProjectId("p1");
        e.setStatus("待办");
        e.setPr("P0");
        e.setOwnerId("wg");
        e.setDueDate("2026-07-24");
        e.setDescription("desc");
        e.setDetailJson("{\"steps\":[{\"text\":\"a\",\"done\":true}],\"activity\":[]}");
        return e;
    }

    @Test
    void patchStatusLogsActivity() {
        when(mapper.selectById("t1")).thenReturn(todo());
        when(mapper.updateById(any(TaskEntity.class))).thenReturn(1);

        TaskDto dto = service.patch("t1", new PatchTaskRequest(null, "已完成", null, null, null));

        assertThat(dto.status()).isEqualTo("已完成");
        assertThat(dto.steps()).hasSize(1);
        assertThat(dto.activity()).isNotEmpty();
        assertThat(dto.activity().get(0).text()).contains("已完成");
    }

    @Test
    void patchNoChangeKeepsStatus() {
        when(mapper.selectById("t1")).thenReturn(todo());
        when(mapper.updateById(any(TaskEntity.class))).thenReturn(1);

        TaskDto dto = service.patch("t1", new PatchTaskRequest(null, "待办", null, null, null));

        assertThat(dto.status()).isEqualTo("待办");
        assertThat(dto.activity()).isEmpty();
    }

    @Test
    void createProducesTodoTask() {
        when(mapper.insert(any(TaskEntity.class))).thenReturn(1);

        TaskDto dto = service.create(new CreateTaskRequest("整理证件盒", "p2", "P1", "wd", "2026-07-30", null, null));

        assertThat(dto.id()).startsWith("t");
        assertThat(dto.status()).isEqualTo("待办");
        assertThat(dto.pr()).isEqualTo("P1");
        assertThat(dto.owner()).isEqualTo("wd");
        assertThat(dto.activity()).isNotEmpty();
    }

    @Test
    void getThrowsWhenMissing() {
        when(mapper.selectById("nope")).thenReturn(null);
        assertThatThrownBy(() -> service.get("nope")).isInstanceOf(NotFoundException.class);
    }
}
