package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.ProjectEntity;
import cn.wayuwa.wao.dto.CreateProjectRequest;
import cn.wayuwa.wao.dto.PatchProjectRequest;
import cn.wayuwa.wao.dto.ProjectDto;
import cn.wayuwa.wao.mapper.ProjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ProjectServiceTest {

    private final ProjectMapper mapper = mock(ProjectMapper.class);
    private final ProjectService service = new ProjectService(mapper, new ObjectMapper(),
            mock(FeishuTaskNotifier.class), mock(MemberService.class));

    @Test
    void assemblesDtoFromColumnsAndDetailJson() {
        ProjectEntity e = new ProjectEntity();
        e.setId("p1");
        e.setName("WAO 1.0 原型");
        e.setArea("career");
        e.setStatus("进行中");
        e.setHealth("良好");
        e.setProgress(62);
        e.setOwnerId("wg");
        e.setStartDate("2026-06-20");
        e.setDueDate("2026-08-15");
        e.setGoal("目标");
        e.setDetailJson("{\"members\":[\"wg\",\"wd\"],\"trend\":[8,15,22],"
                + "\"milestones\":[{\"name\":\"信息架构定稿\",\"date\":\"7月5日\",\"state\":2}]}");
        when(mapper.selectById("p1")).thenReturn(e);

        ProjectDto dto = service.get("p1");

        assertThat(dto.owner()).isEqualTo("wg");
        assertThat(dto.progress()).isEqualTo(62);
        assertThat(dto.members()).containsExactly("wg", "wd");
        assertThat(dto.trend()).containsExactly(8, 15, 22);
        assertThat(dto.milestones()).hasSize(1);
        assertThat(dto.milestones().get(0).state()).isEqualTo(2);
        assertThat(dto.blockers()).isEmpty();
    }

    @Test
    void getThrowsWhenMissing() {
        when(mapper.selectById("nope")).thenReturn(null);
        assertThatThrownBy(() -> service.get("nope")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void createProducesPlanningProject() {
        when(mapper.insert(any(ProjectEntity.class))).thenReturn(1);

        ProjectDto dto = service.create(new CreateProjectRequest("阳台花园改造", "family", "把阳台变花园", true), "wd");

        assertThat(dto.id()).startsWith("p");
        assertThat(dto.status()).isEqualTo("规划中");
        assertThat(dto.progress()).isZero();
        assertThat(dto.members()).containsExactly("wg", "wd");
        assertThat(dto.owner()).isEqualTo("wd");
        assertThat(dto.activity()).isNotEmpty();
        assertThat(dto.notifyEnabled()).isTrue();
    }

    @Test
    void notificationSwitchCanBeTurnedOffAndBackOn() {
        ProjectEntity e = new ProjectEntity();
        e.setId("p2");
        e.setName("家庭资料归档");
        e.setNotifyEnabled(true);
        when(mapper.selectById("p2")).thenReturn(e);
        when(mapper.updateById(any(ProjectEntity.class))).thenReturn(1);

        assertThat(service.update("p2", new PatchProjectRequest(null, null, null, null, false)).notifyEnabled())
                .isFalse();
        assertThat(service.update("p2", new PatchProjectRequest(null, null, null, null, true)).notifyEnabled())
                .isTrue();
        // 不带这个字段的更新（改名、换图标）不该顺手把开关重置掉
        assertThat(service.update("p2", new PatchProjectRequest("改个名字", null, null, null, null)).notifyEnabled())
                .isTrue();
    }

    @Test
    void legacyProjectsWithoutTheColumnCountAsNotifying() {
        ProjectEntity e = new ProjectEntity();
        e.setId("p3");
        e.setName("健康重启计划");
        when(mapper.selectById("p3")).thenReturn(e);

        assertThat(service.get("p3").notifyEnabled()).isTrue();
    }
}
