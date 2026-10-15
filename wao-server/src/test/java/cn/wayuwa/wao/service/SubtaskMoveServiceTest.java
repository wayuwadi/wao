package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.*;
import cn.wayuwa.wao.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SubtaskMoveServiceTest {
    final TaskMapper mapper = mock(TaskMapper.class);
    final TaskService tasks = mock(TaskService.class);
    final WorkItemNumberMapper numbers = mock(WorkItemNumberMapper.class);
    final ObjectMapper json = new ObjectMapper();
    final SubtaskMoveService service = new SubtaskMoveService(mapper, tasks, numbers, json);
    Step sub(String id, String parent) { return new Step(id, false, id, "正文", List.of(), 7L, parent, "m1", "2026-09-18"); }
    TaskEntity task(String id, List<Step> subs) throws Exception {
        var task = new TaskEntity(); task.setId(id);
        var detail = json.createObjectNode(); detail.set("subs", json.valueToTree(subs)); detail.put("unknownField", "保留");
        detail.put("schedulePeriod", "EVENING");
        task.setDetailJson(json.writeValueAsString(detail));
        when(mapper.lockById(id)).thenReturn(task);
        when(tasks.get(eq(id), anyString())).thenReturn(mock(TaskDto.class));
        return task;
    }
    @Test void movesWholeTreeAndKeepsMetadataNumbersAndOtherFields() throws Exception {
        var from = List.of(sub("a", null), sub("b", "a"), sub("c", "b"), sub("d", null));
        var to = List.of(sub("z", null)); task("s", from); task("t", to);
        service.move("s", "a", new MoveSubtaskRequest("t", "z", from, to), "m1");
        var captured = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mapper).updateDetail(eq("t"), captured.capture());
        var saved = json.readTree(captured.getValue());
        assertThat(saved.path("unknownField").asText()).isEqualTo("保留");
        assertThat(saved.path("schedulePeriod").asText()).isEqualTo("EVENING");
        assertThat(saved.path("subs").size()).isEqualTo(4);
        assertThat(saved.path("subs").get(1).path("parentId").asText()).isEqualTo("z");
        assertThat(saved.path("subs").get(2).path("owner").asText()).isEqualTo("m1");
        assertThat(saved.path("subs").get(3).path("due").asText()).isEqualTo("2026-09-18");
        for (String id : List.of("a", "b", "c")) verify(numbers).move("s/" + id, "t/" + id);
        verify(numbers, never()).move(eq("s/d"), anyString());
    }
    @Test void reparentsWithinTaskAndRejectsCycles() throws Exception {
        var subs = List.of(sub("a", null), sub("b", "a"), sub("z", null)); task("s", subs);
        assertThatThrownBy(() -> service.move("s", "a", new MoveSubtaskRequest("s", "b", subs, subs), "m1")).isInstanceOf(BadRequestException.class);
        verify(mapper, never()).updateDetail(anyString(), anyString());
        service.move("s", "a", new MoveSubtaskRequest("s", "z", subs, subs), "m1");
        verify(mapper, times(1)).updateDetail(eq("s"), anyString()); verifyNoInteractions(numbers);
    }
    @Test void rejectsStaleSnapshotsAndActiveExecutionBeforeWriting() throws Exception {
        var subs = List.of(sub("a", null)); task("s", subs); task("t", List.of());
        assertThatThrownBy(() -> service.move("s", "a", new MoveSubtaskRequest("t", null, List.of(), List.of()), "m1")).hasMessageContaining("已有更新");
        when(mapper.countUnfinishedRuns("s", "t")).thenReturn(1);
        assertThatThrownBy(() -> service.move("s", "a", new MoveSubtaskRequest("t", null, subs, List.of()), "m1")).hasMessageContaining("AI 执行");
        verify(mapper, never()).updateDetail(anyString(), anyString());
    }
    @Test void checksTargetVisibilityBeforeLocksOrWrites() {
        when(tasks.get("t", "m1")).thenThrow(new BadRequestException("不可见"));
        assertThatThrownBy(() -> service.move("s", "a", new MoveSubtaskRequest("t", null, List.of(), List.of()), "m1")).hasMessageContaining("不可见");
        verifyNoInteractions(mapper, numbers);
    }
    @Test void validatesMissingParentsDuplicatesAndDates() {
        assertThatThrownBy(() -> SubtaskTree.validate(List.of(sub("a", "missing")))).hasMessageContaining("不存在");
        assertThatThrownBy(() -> SubtaskTree.validate(List.of(sub("a", null), sub("a", null)))).hasMessageContaining("重复");
        assertThatThrownBy(() -> SubtaskTree.validate(List.of(new Step("a", false, "a", "", List.of(), null, null, null, "bad")))).hasMessageContaining("日期");
    }

    @Test void promotionPreservesTreeMetadataNumbersAndRemainingSource() throws Exception {
        var root = new Step("提升标题", false, "a", "原描述", List.of(new ImageRef("验收图片", "/api/images/synthetic/raw")), 7L, null, "m1", "2026-10-20",
                "待验证", "creator", "2026-10-10T08:30:00+08:00", List.of("doc1"), List.of(new CommentDto("c1", "m1", "2026-10-10T09:00:00", "保留评论")), "P1");
        var subs = List.of(root, sub("b", "a"), sub("c", "b"), sub("d", null));
        var source = task("s", subs); source.setProjectId("project1"); source.setOwnerId("m1");
        when(tasks.get(anyString(), eq("m1"))).thenReturn(mock(TaskDto.class));
        service.promote("s", "a", new PromoteSubtaskRequest(subs), "m1");
        var captured = org.mockito.ArgumentCaptor.forClass(TaskEntity.class);
        verify(mapper).insert(captured.capture());
        TaskEntity promoted = captured.getValue();
        assertThat(promoted.getId()).hasSize(32);
        assertThat(promoted.getTitle()).isEqualTo("提升标题");
        assertThat(promoted.getDescription()).isEqualTo("原描述");
        assertThat(promoted.getProjectId()).isEqualTo("project1");
        assertThat(promoted.getStatus()).isEqualTo("待验证");
        assertThat(promoted.getPr()).isEqualTo("P1");
        assertThat(promoted.getCreatedBy()).isEqualTo("creator");
        assertThat(promoted.getCreatedAt()).isEqualTo(java.time.LocalDateTime.parse("2026-10-10T08:30:00"));
        assertThat(promoted.getDueDate()).isEqualTo("2026-10-20");
        var detail = json.readTree(promoted.getDetailJson());
        assertThat(detail.path("docs").get(0).asText()).isEqualTo("doc1");
        assertThat(detail.path("images").get(0).path("name").asText()).isEqualTo("验收图片");
        assertThat(detail.path("comments").get(0).path("text").asText()).isEqualTo("保留评论");
        assertThat(detail.path("subs").size()).isEqualTo(2);
        assertThat(detail.path("subs").get(0).path("parentId").isNull()).isTrue();
        assertThat(detail.path("subs").get(1).path("parentId").asText()).isEqualTo("b");
        assertThat(json.readTree(source.getDetailJson()).path("subs").get(0).path("id").asText()).isEqualTo("d");
        assertThat(json.readTree(source.getDetailJson()).path("unknownField").asText()).isEqualTo("保留");
        verify(numbers).move("s/a", promoted.getId() + "/");
        verify(numbers).move("s/b", promoted.getId() + "/b");
        verify(numbers).move("s/c", promoted.getId() + "/c");
        verify(numbers, never()).move(eq("s/d"), anyString());
    }

    @Test void promotionRejectsStaleSnapshotActiveRunAndInvisibleSourceBeforeWrites() throws Exception {
        var subs = List.of(sub("a", null)); task("s", subs);
        assertThatThrownBy(() -> service.promote("s", "a", new PromoteSubtaskRequest(List.of()), "m1")).hasMessageContaining("已有更新");
        when(mapper.countUnfinishedRuns("s", "s")).thenReturn(1);
        assertThatThrownBy(() -> service.promote("s", "a", new PromoteSubtaskRequest(subs), "m1")).hasMessageContaining("AI 执行");
        when(tasks.get("s", "m1")).thenThrow(new BadRequestException("不可见"));
        assertThatThrownBy(() -> service.promote("s", "a", new PromoteSubtaskRequest(subs), "m1")).hasMessageContaining("不可见");
        verify(mapper, never()).insert(any(TaskEntity.class));
        verify(mapper, never()).updateDetail(anyString(), anyString());
        verifyNoInteractions(numbers);
    }

    @Test void promotionReplayReturnsExistingTaskAndDeletedTargetIsNotRecreated() throws Exception {
        task("s", List.of());
        String id = "t" + UUID.nameUUIDFromBytes("s/a".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().replace("-", "").substring(0, 31);
        when(mapper.selectById(id)).thenReturn(new TaskEntity());
        when(tasks.get(eq(id), anyString())).thenReturn(mock(TaskDto.class));
        assertThat(service.promote("s", "a", new PromoteSubtaskRequest(List.of(sub("a", null))), "m1")).hasSize(2);
        when(mapper.selectById(id)).thenReturn(null);
        assertThatThrownBy(() -> service.promote("s", "a", new PromoteSubtaskRequest(List.of(sub("a", null))), "m1")).hasMessageContaining("已有更新");
        verify(mapper, never()).insert(any(TaskEntity.class));
        verifyNoInteractions(numbers);
    }
}
