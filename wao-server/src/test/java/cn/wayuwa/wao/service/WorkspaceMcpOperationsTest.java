package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import cn.wayuwa.wao.common.*;
import cn.wayuwa.wao.dto.*;
import cn.wayuwa.wao.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class WorkspaceMcpOperationsTest {
    @Test void systemSourceCompatibilityPatchRequiresRevisionAndReadOnlyCannotMark() throws Exception {
        docVisible(note("source", "m1", "保留正文", List.of("已有")));
        var patch = json.readTree("{\"document_id\":\"source\",\"system_generated\":true,\"expected_rev\":4}");
        tools.call("update_document", patch, writer);
        var captured = ArgumentCaptor.forClass(PatchDocRequest.class);
        verify(knowledge).updateDoc(eq("source"), captured.capture());
        assertThat(captured.getValue().systemGenerated()).isTrue();
        assertThat(captured.getValue().blocks()).isNull(); assertThat(captured.getValue().tags()).isNull();
        assertThatThrownBy(() -> tools.call("update_document", json.readTree("{\"document_id\":\"source\",\"system_generated\":true}"), writer))
                .hasMessageContaining("expected_rev");
        assertThatThrownBy(() -> tools.call("update_document", patch, token(false, false))).hasMessageContaining("没有写权限");
        assertThatThrownBy(() -> tools.call("create_note", json.readTree("{\"system_generated\":\"true\"}"), writer)).hasMessageContaining("类型");
    }

    @Test void noteListExcludesSystemBeforePaginationButKeepsOwnerInSummary() throws Exception {
        DocDto personal = note("personal", "m1", "个人", List.of());
        DocDto generated = new DocDto("system", "系统介绍", "m1", personal.created(), personal.updated(), false,
                List.of(), personal.blocks(), null, List.of(), "kb-notes", "f", 4, List.of(), true, true);
        when(knowledge.listDocCatalog()).thenReturn(List.of(generated, personal));
        var all = json.valueToTree(tools.call("list_notes", json.readTree("{}"), writer));
        assertThat(all.path("total").asInt()).isEqualTo(2);
        var filtered = json.valueToTree(tools.call("list_notes", json.readTree("{\"include_system\":false,\"limit\":1}"), writer));
        assertThat(filtered.path("total").asInt()).isEqualTo(1);
        assertThat(filtered.path("items").get(0).path("id").asText()).isEqualTo("personal");
        assertThat(filtered.path("items").get(0).path("author").asText()).isEqualTo("m1");
    }
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final ProjectService projects = mock(ProjectService.class);
    final TaskService tasks = mock(TaskService.class);
    final KnowledgeService knowledge = mock(KnowledgeService.class);
    final NoteTagService tags = mock(NoteTagService.class);
    final MemberService members = mock(MemberService.class);
    final TaskHierarchyService hierarchy = mock(TaskHierarchyService.class);
    final SubtaskMoveService move = mock(SubtaskMoveService.class);
    final TodoDayService days = mock(TodoDayService.class);
    final TodoOperationService daily = mock(TodoOperationService.class);
    final AccountMapper accounts = mock(AccountMapper.class);
    final DocMapper docs = mock(DocMapper.class);
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final WorkspaceMcpOperations operations = new WorkspaceMcpOperations(projects, tasks, knowledge, tags, members,
            hierarchy, move, days, daily, accounts, docs, jdbc, json);
    final WorkspaceMcpTools tools = new WorkspaceMcpTools(projects, tasks, knowledge, json, operations);
    final WorkspaceMcpTokenService.AuthenticatedToken writer = token(false, true);

    static WorkspaceMcpTokenService.AuthenticatedToken token(boolean daily, boolean write) {
        List<String> names = new ArrayList<>(WorkspaceCapabilityPolicy.READ);
        if (write) names.addAll(WorkspaceCapabilityPolicy.WRITE);
        if (!daily) names.removeAll(List.of("get_todo_day", "list_todo_days", "get_daily_operation", "schedule_task", "record_daily_note"));
        return new WorkspaceMcpTokenService.AuthenticatedToken("token", "m1", write, false,
                new WorkspaceCapabilityPolicy(names, null, null));
    }

    @org.junit.jupiter.api.BeforeEach void noteKb() { when(knowledge.getKb("kb-notes")).thenReturn(mock(KbDto.class)); }

    @Test void dailyToolsRequireIndependentGrantAndReadOnlyCannotSave() throws Exception {
        assertThat(tools.list(true).toString()).doesNotContain("schedule_task", "get_todo_day");
        assertThat(tools.list(false, true).toString()).contains("get_todo_day").doesNotContain("schedule_task");
        assertThatThrownBy(() -> tools.call("get_todo_day", json.readTree("{\"date\":\"2026-10-04\"}"), writer))
                .hasMessageContaining("未授权");
        var reader = token(true, false);
        assertThatThrownBy(() -> tools.call("schedule_task", json.readTree("{}"), reader)).hasMessageContaining("没有写权限");
        verifyNoInteractions(daily, days);
    }

    @Test void rejectsUnknownFieldsWrongTypesAndMissingScheduleKeysBeforeMutation() throws Exception {
        assertThatThrownBy(() -> tools.call("list_notes", json.readTree("{\"mine_only\":\"false\"}"), writer)).hasMessageContaining("类型");
        assertThatThrownBy(() -> tools.call("create_note", json.readTree("{\"auto_polish\":true}"), writer)).hasMessageContaining("不支持字段");
        var dailyWriter = token(true, true);
        assertThatThrownBy(() -> tools.call("schedule_task", json.readTree("""
                {"task_id":"t1","operation_id":"b8eafee6-c64f-44a8-84d4-7785b47e2151","expected_edit_version":"v1","start":null}
                """), dailyWriter)).hasMessageContaining("start_at");
        verifyNoInteractions(daily, knowledge);
    }

    @Test void tagMergeUsesMetadataPatchAndNeverReplacesBodyOrOtherFields() throws Exception {
        DocDto d = note("d1", "m1", "tag only", List.of("已有")); docVisible(d);
        operations.call("update_note_tags", json.readTree("""
                {"document_id":"d1","mode":"add","tags":["WAO功能建设","已有"],"expected_rev":4}
                """), writer);
        var patch = ArgumentCaptor.forClass(PatchDocRequest.class);
        verify(knowledge).updateDoc(eq("d1"), patch.capture());
        assertThat(patch.getValue().tags()).containsExactly("已有", "WAO功能建设");
        assertThat(patch.getValue().expectedRev()).isEqualTo(4);
        assertThat(patch.getValue().blocks()).isNull(); assertThat(patch.getValue().content()).isNull();
        assertThat(patch.getValue().title()).isNull(); assertThat(patch.getValue().folderId()).isNull();
        verify(tags).create("WAO功能建设");
    }

    @Test void clearRequiresRevisionAndRejectsUnusedTagsInsteadOfIgnoringThem() throws Exception {
        assertThatThrownBy(() -> tools.call("update_note_tags", json.readTree("{\"document_id\":\"d1\",\"mode\":\"clear\"}"), writer))
                .hasMessageContaining("expected_rev");
        docVisible(note("d1", "m1", "body", List.of()));
        assertThatThrownBy(() -> tools.call("update_note_tags", json.readTree("{\"document_id\":\"d1\",\"mode\":\"clear\",\"tags\":[],\"expected_rev\":4}"), writer))
                .hasMessageContaining("不接受 tags");
        verify(knowledge, never()).updateDoc(any(), any());
    }

    @Test void noteListDefaultsToMineWithCompletePaginationAndAllTagFilter() throws Exception {
        var kb = mock(KbDto.class); when(knowledge.getKb("kb-notes")).thenReturn(kb);
        List<DocDto> values = new ArrayList<>();
        for (int i = 0; i < 105; i++) values.add(note("d" + i, "m1", "首批能力", List.of("建设", "WAO")));
        values.add(note("other", "m2", "首批能力", List.of("建设", "WAO")));
        when(knowledge.listDocCatalog()).thenReturn(values);
        var page = (WorkspaceMcpOperations.Page<?>) tools.call("list_notes", json.readTree("""
                {"keyword":"首批","tags":["建设","WAO"],"offset":100,"limit":10,"from":"2026-10-01","to":"2026-10-04"}
                """), writer);
        assertThat(page.total()).isEqualTo(105); assertThat(page.items()).hasSize(5); assertThat(page.hasMore()).isFalse();
        assertThatThrownBy(() -> tools.call("list_notes", json.readTree("{\"from\":\"2026-10-05\",\"to\":\"2026-10-01\"}"), writer))
                .hasMessageContaining("不能晚于");
    }

    @Test void taskHistoryOpenFilterPreservesParentsWithUnfinishedChildren() throws Exception {
        TaskDto closed = task("done", "已完成", List.of(new Step("已完成", true, "s1", "", List.of())));
        TaskDto unfinished = task("remaining", "已完成", List.of(new Step("待验证", false, "s2", "", List.of())));
        when(tasks.list("m1")).thenReturn(List.of(closed, unfinished));
        var page = (WorkspaceMcpOperations.Page<?>) tools.call("list_tasks", json.readTree("{\"status\":\"open\"}"), writer);
        assertThat(page.total()).isEqualTo(1); assertThat(page.items().toString()).contains("remaining").doesNotContain("id=done");
        assertThat(((WorkspaceMcpOperations.Page<?>) tools.call("list_tasks", json.readTree("{}"), writer)).total()).isEqualTo(2);
    }

    @Test void searchCannotReadPrivateProjectBodyAndFindsBodyTermsBeyondTitle() throws Exception {
        DocDto allowed = new DocDto("allowed", "标题", "m2", "2026-10-04", "2026-10-04", false,
                List.of(), List.of(new DocBlockDto(null, null, "正文含测试命中", null, null)), null, List.of(), "shared", "f", 1, List.of(), false);
        DocDto denied = new DocDto("private", "标题", "m2", "2026-10-04", "2026-10-04", false,
                List.of(), List.of(), null, List.of(), "hidden", "f", 1, List.of(), false);
        var shared = mock(KbDto.class); when(shared.id()).thenReturn("shared");
        var hidden = mock(KbDto.class); when(hidden.id()).thenReturn("hidden");
        when(knowledge.listKbs()).thenReturn(List.of(shared, hidden));
        when(knowledge.projectIdForKb("hidden")).thenReturn("private-project");
        doThrow(new NotFoundException("项目不存在")).when(projects).requireAccess("private-project", "m1");
        when(knowledge.listDocCatalog()).thenReturn(List.of(allowed, denied));
        when(knowledge.getDoc("allowed")).thenReturn(allowed);
        var page = (WorkspaceMcpOperations.Page<?>) tools.call("search_workspace", json.readTree("{\"keyword\":\"测试命中\",\"kinds\":[\"document\"]}"), writer);
        assertThat(page.total()).isEqualTo(1); assertThat(page.items().toString()).contains("/kb/shared?doc=allowed", "正文含测试命中");
        verify(knowledge, never()).getDoc("private");
    }

    @Test void taskMutationRequiresCASAndReorderCannotOmitAChild() throws Exception {
        TaskDto old = task("t1", "待办", List.of(new Step("原子任务", false, "s1", "", List.of())));
        when(tasks.get("t1", "m1")).thenReturn(old);
        assertThatThrownBy(() -> tools.call("update_task", json.readTree("{\"task_id\":\"t1\",\"parts\":[]}"), writer)).hasMessageContaining("expected_edit_version");
        assertThatThrownBy(() -> tools.call("create_subtask", json.readTree("{\"task_id\":\"t1\",\"text\":\"新\",\"expected_edit_version\":\"stale\"}"), writer)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> tools.call("reorder_subtasks", json.readTree("{\"task_id\":\"t1\",\"subtask_ids\":[],\"expected_edit_version\":\"v1\"}"), writer)).hasMessageContaining("全部");
        verify(tasks, never()).replace(any(), any(), any());
    }

    @Test void subtaskEditClearsParentExplicitlyAndRetainsSiblingAndTaskSchedule() throws Exception {
        TaskDto old = task("t1", "待办", List.of(new Step("父", false, "s1", "", List.of()), new Step("子", false, "s2", "正文", List.of(), null, "s1", null, null)));
        when(tasks.get("t1", "m1")).thenReturn(old);
        tools.call("update_subtask", json.readTree("{\"task_id\":\"t1\",\"subtask_id\":\"s2\",\"text\":\"改名\",\"parent_id\":null,\"expected_edit_version\":\"v1\"}"), writer);
        var captured = ArgumentCaptor.forClass(TaskDto.class);
        verify(tasks).replace(eq("t1"), captured.capture(), eq("m1"));
        TaskDto next = captured.getValue(); assertThat(next.subs()).hasSize(2);
        assertThat(next.subs().get(1).parentId()).isNull(); assertThat(next.subs().get(1).desc()).isEqualTo("正文");
        assertThat(next.start()).isEqualTo(old.start()); assertThat(next.due()).isEqualTo(old.due());
        assertThat(next.editVersion()).isEqualTo("v1");
    }

    @Test void moveSubtaskRejectsStaleTargetSnapshotBeforeBusinessMutation() throws Exception {
        when(tasks.get("t1", "m1")).thenReturn(task("t1", "待办", List.of()));
        when(tasks.get("t2", "m1")).thenReturn(task("t2", "待办", List.of()));
        assertThatThrownBy(() -> tools.call("move_subtask", json.readTree("{\"task_id\":\"t1\",\"subtask_id\":\"s1\",\"target_task_id\":\"t2\",\"expected_source_version\":\"subs-v1\",\"expected_target_version\":\"stale\"}"), writer)).isInstanceOf(ConflictException.class);
        verifyNoInteractions(move);
    }

    @Test void stableNoteCreateReplayReturnsLatestWithoutOverwritingLaterEdits() throws Exception {
        String id = "7e2bfde1eb7446128e30d8175319f229";
        var identity = new cn.wayuwa.wao.domain.AccountEntity();
        identity.setId("a1"); identity.setMemberId("m1"); identity.setStatus("ACTIVE");
        when(accounts.findByMemberId("m1")).thenReturn(identity);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<String>>any(), eq("m1"), eq(id)))
                .thenReturn(List.of());
        var a = json.readTree("{\"document_id\":\"" + id + "\",\"system_generated\":true,\"text\":\"  保留原样\\n\\n第二段  \"}");
        DocDto created = note(id, "m1", "  保留原样\n\n第二段  ", List.of());
        when(knowledge.createNote(eq(id), any(), eq("m1"))).thenReturn(created);
        assertThat(tools.call("create_note", a, writer)).isEqualTo(created);
        var request = ArgumentCaptor.forClass(CreateNoteRequest.class);
        verify(knowledge).createNote(eq(id), request.capture(), eq("m1"));
        assertThat(request.getValue().blocks().get(0).p()).isEqualTo("  保留原样\n\n第二段  ");
        assertThat(request.getValue().autoPolish()).isFalse();
        assertThat(request.getValue().systemGenerated()).isTrue();
        var hash = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(startsWith("INSERT INTO workspace_mcp_note_receipt"), eq("m1"), eq(id), hash.capture());
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<String>>any(), eq("m1"), eq(id)))
                .thenReturn(List.of(hash.getValue()));
        DocDto later = note(id, "m1", "之后浏览器保存的正文", List.of("新标签")); docVisible(later);
        assertThat(tools.call("create_note", a, writer)).isEqualTo(later);
        verify(knowledge, times(1)).createNote(eq(id), any(), eq("m1"));
        assertThatThrownBy(() -> tools.call("create_note", json.readTree("{\"document_id\":\"" + id + "\",\"text\":\"另一份\"}"), writer))
                .isInstanceOf(ConflictException.class);
        verify(knowledge, never()).updateDoc(any(), any());
    }

    @Test void dailyRecordDerivesAccountFromTokenAndCallsNoAiPath() throws Exception {
        var identity = new cn.wayuwa.wao.domain.AccountEntity(); identity.setId("a1"); identity.setStatus("ACTIVE");
        when(accounts.findByMemberId("m1")).thenReturn(identity);
        var token = token(true, true);
        tools.call("record_daily_note", json.readTree("{\"operation_id\":\"d6e1a637-ac88-4e80-9f8a-e8a6a96c90a3\",\"document_id\":\"test-note\",\"business_date\":\"2026-10-04\",\"text\":\"进展\"}"), token);
        var request = ArgumentCaptor.forClass(TodoOperationService.NoteRequest.class);
        verify(daily).noteWithoutAi(eq("d6e1a637-ac88-4e80-9f8a-e8a6a96c90a3"), request.capture(), eq("a1"), eq("m1"));
        assertThat(request.getValue().autoPolish()).isFalse(); assertThat(request.getValue().autoImage()).isFalse();
        verify(daily, never()).note(any(), any(), any(), any());
    }

    @Test void operationReceiptNeverLoadsPrivateRoutineRulesFromTheSharedReceiptTable() throws Exception {
        var identity = new cn.wayuwa.wao.domain.AccountEntity(); identity.setId("a1"); identity.setStatus("ACTIVE");
        when(accounts.findByMemberId("m1")).thenReturn(identity);
        String operation = "d6e1a637-ac88-4e80-9f8a-e8a6a96c90a3";
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<String>>any(), eq("a1"), eq(operation)))
                .thenReturn(List.of("ROUTINE_RULE"), List.of(), List.of("SCHEDULE"));
        var request = json.createObjectNode().put("operation_id", operation);
        for (int i = 0; i < 2; i++) {
            var receipt = (TodoOperationService.Receipt) tools.call("get_daily_operation", request, token(true, false));
            assertThat(receipt.status()).isEqualTo("UNCONFIRMED");
            assertThat(receipt.result()).isNull(); assertThat(receipt.type()).isNull();
        }
        verifyNoInteractions(daily);
        var allowed = new TodoOperationService.Receipt("COMMITTED", operation, "SCHEDULE", null);
        when(daily.receipt(operation, "a1", "m1")).thenReturn(allowed);
        assertThat(tools.call("get_daily_operation", request, token(true, false))).isEqualTo(allowed);
    }

    private DocDto note(String id, String author, String text, List<String> tags) {
        return new DocDto(id, "标题", author, "2026-10-04T10:00:00", "2026-10-04T10:00:00", false, List.of(),
                List.of(new DocBlockDto(null, null, text, null, null)), null, List.of(), "kb-notes", "f", 4, tags, true);
    }
    private void docVisible(DocDto d) { when(knowledge.getDoc(d.id())).thenReturn(d); when(knowledge.getKb(d.kbId())).thenReturn(mock(KbDto.class)); }
    private TaskDto task(String id, String status, List<Step> subs) {
        return new TaskDto(id, "任务", "p", status, "P2", "m1", List.of(), "2026-10-04", "2026-10-10", "", "正文",
                List.of(), subs, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of())
                .withEditVersion("v1").withSubtaskVersion("subs-v1");
    }
}
