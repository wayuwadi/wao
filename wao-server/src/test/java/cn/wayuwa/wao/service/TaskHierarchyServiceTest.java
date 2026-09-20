package cn.wayuwa.wao.service;

import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.TaskDto;
import cn.wayuwa.wao.mapper.TaskMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TaskHierarchyServiceTest {
    final TaskMapper mapper = mock(TaskMapper.class);
    final TaskService tasks = mock(TaskService.class);
    final TaskHierarchyService service = new TaskHierarchyService(mapper, tasks);
    TaskEntity row(String id, String parent) {
        TaskEntity row = new TaskEntity(); row.setId(id); row.setProjectId("p"); row.setParentTaskId(parent);
        row.setDescription("正文"); row.setDetailJson("{\"files\":[{\"id\":\"file\"}],\"custom\":[1],\"subs\":[{\"id\":\"s\"}]}");
        TaskDto dto = mock(TaskDto.class); when(dto.project()).thenReturn("p"); when(dto.id()).thenReturn(id);
        when(tasks.get(id, "member")).thenReturn(dto);
        return row;
    }
    @Test void moveOnlyUpdatesRelationshipAndKeepsOriginalIdentityAndRichData() {
        TaskEntity a = row("a", null), b = row("b", null); String detail = a.getDetailJson();
        when(mapper.lockProjectTasks("p")).thenReturn(List.of(a,b));
        service.move("a", "b", null, "member");
        verify(mapper).updateParent("a", "b");
        verify(mapper, never()).updateById(any(TaskEntity.class));
        verify(mapper, never()).deleteById(anyString());
        assertThat(a.getDescription()).isEqualTo("正文"); assertThat(a.getDetailJson()).isEqualTo(detail);
    }
    @Test void rejectsSelfAndDescendantWithoutWriting() {
        TaskEntity a = row("a", null), b = row("b", "a"), c = row("c", "b");
        when(mapper.lockProjectTasks("p")).thenReturn(List.of(a,b,c));
        assertThatThrownBy(() -> service.move("a", "a", null, "member")).hasMessageContaining("后代");
        assertThatThrownBy(() -> service.move("a", "c", null, "member")).hasMessageContaining("后代");
        verify(mapper, never()).updateParent(anyString(), any());
    }
    @Test void rejectsCrossProjectAndStaleRelationship() {
        TaskEntity a = row("a", "b"); row("outside", null);
        when(mapper.lockProjectTasks("p")).thenReturn(List.of(a));
        assertThatThrownBy(() -> service.move("a", "outside", "b", "member")).hasMessageContaining("同一项目");
        assertThatThrownBy(() -> service.move("a", null, null, "member")).hasMessageContaining("其他窗口");
        verify(mapper, never()).updateParent(anyString(), any());
    }
    @Test void secondConcurrentCrossMoveUsesLockedCurrentTree() {
        TaskEntity a = row("a", "b"), b = row("b", null);
        when(mapper.lockProjectTasks("p")).thenReturn(List.of(a,b));
        assertThatThrownBy(() -> service.move("b", "a", null, "member")).hasMessageContaining("后代");
        verify(mapper, never()).updateParent(anyString(), any());
    }
    @Test void detachPreservesChildrenAndPermissionIsCheckedBeforeLocking() {
        TaskEntity a = row("a", "b"), child = row("c", "a");
        when(mapper.lockProjectTasks("p")).thenReturn(List.of(a,child));
        service.move("a", null, "b", "member");
        verify(mapper).updateParent("a", null); assertThat(child.getParentTaskId()).isEqualTo("a");
        reset(mapper);
        when(tasks.get("hidden", "member")).thenThrow(new cn.wayuwa.wao.common.NotFoundException("不可见"));
        assertThatThrownBy(() -> service.move("a", "hidden", "b", "member")).hasMessageContaining("不可见");
        verifyNoInteractions(mapper);
    }
}
