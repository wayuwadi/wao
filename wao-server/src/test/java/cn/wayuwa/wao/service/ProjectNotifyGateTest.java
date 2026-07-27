package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.domain.ProjectEntity;
import cn.wayuwa.wao.mapper.ProjectMapper;
import org.junit.jupiter.api.Test;

class ProjectNotifyGateTest {

    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final ProjectNotifyGate gate = new ProjectNotifyGate(projects);

    private ProjectEntity project(String id, Boolean notifyEnabled) {
        ProjectEntity e = new ProjectEntity();
        e.setId(id);
        e.setName(id);
        e.setNotifyEnabled(notifyEnabled);
        return e;
    }

    @Test
    void mutedProjectStopsNotifications() {
        when(projects.selectById("p2")).thenReturn(project("p2", false));

        assertThat(gate.enabled("p2")).isFalse();
    }

    @Test
    void enabledProjectStillNotifies() {
        when(projects.selectById("p1")).thenReturn(project("p1", true));

        assertThat(gate.enabled("p1")).isTrue();
    }

    @Test
    void unknownOrLegacyProjectFallsBackToEnabled() {
        // 查不到项目、或老数据没有开关值时宁可多发一条，也别悄悄吞掉通知
        when(projects.selectById("gone")).thenReturn(null);
        when(projects.selectById("legacy")).thenReturn(project("legacy", null));

        assertThat(gate.enabled("gone")).isTrue();
        assertThat(gate.enabled("legacy")).isTrue();
        assertThat(gate.enabled(null)).isTrue();
        assertThat(gate.enabled(" ")).isTrue();
    }
}
