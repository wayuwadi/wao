package cn.wayuwa.wao.service;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.CreateTaskRequest;
import cn.wayuwa.wao.dto.PatchTaskRequest;
import cn.wayuwa.wao.dto.TaskDto;
import cn.wayuwa.wao.dto.MemberDto;
import cn.wayuwa.wao.mapper.TaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class TaskServiceTest {

    private final TaskMapper mapper = mock(TaskMapper.class);
    private final FeishuCommentNotifier notifier = mock(FeishuCommentNotifier.class);
    private final MemberService members = mock(MemberService.class);
    private final ProjectService projects = mock(ProjectService.class);
    private final FeishuTaskNotifier taskNotifier = mock(FeishuTaskNotifier.class);
    private final TaskService service = new TaskService(mapper, new ObjectMapper(),
            notifier, taskNotifier, members, projects);

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

        TaskDto dto = service.patch("t1", new PatchTaskRequest(null, "已完成", null, null, null, null, null), "wg");

        assertThat(dto.status()).isEqualTo("已完成");
        assertThat(dto.steps()).hasSize(1);
        assertThat(dto.activity()).isNotEmpty();
        assertThat(dto.activity().get(0).text()).contains("已完成");
    }

    @Test
    void patchNoChangeKeepsStatus() {
        when(mapper.selectById("t1")).thenReturn(todo());
        when(mapper.updateById(any(TaskEntity.class))).thenReturn(1);

        TaskDto dto = service.patch("t1", new PatchTaskRequest(null, "待办", null, null, null, null, null), "wg");

        assertThat(dto.status()).isEqualTo("待办");
        assertThat(dto.activity()).isEmpty();
    }

    @Test
    void movingIntoCollectingSharesTheTaskToTheGroupOnce() {
        TaskEntity entity = todo();
        when(mapper.selectById("t1")).thenReturn(entity);
        when(mapper.updateById(any(TaskEntity.class))).thenReturn(1);
        when(projects.get("p1")).thenThrow(new NotFoundException("项目不存在：p1"));
        when(taskNotifier.feishuOpenId("wg")).thenReturn("ou_wg");

        service.patch("t1", new PatchTaskRequest(null, "收集中", null, null, null, null, null), "wg");
        // 已经在收集中时再保存别的字段，不该再打扰群里一次
        service.patch("t1", new PatchTaskRequest("改个标题", "收集中", null, null, null, null, null), "wg");

        verify(taskNotifier, times(1)).notifyCollecting("t1", "p1", "设计任务详情抽屉交互稿",
                "p1", "desc", "ou_wg");
    }

    @Test
    void patchUpdatesStartAndDueDates() {
        TaskEntity entity = todo();
        when(mapper.selectById("t1")).thenReturn(entity);
        when(mapper.updateById(any(TaskEntity.class))).thenReturn(1);

        TaskDto dto = service.patch("t1",
                new PatchTaskRequest(null, null, null, null, null, "2026-07-20", "2026-08-01"), "wg");

        assertThat(dto.start()).isEqualTo("2026-07-20");
        assertThat(dto.due()).isEqualTo("2026-08-01");
        assertThat(dto.activity()).extracting(cn.wayuwa.wao.dto.Activity::text)
                .contains("将开始日期改为 2026-07-20", "将截止日期改为 2026-08-01");
    }

    @Test
    void patchClearsDatesWithEmptyStringButIgnoresNull() {
        TaskEntity entity = todo();
        entity.setStartDate("2026-07-20");
        when(mapper.selectById("t1")).thenReturn(entity);
        when(mapper.updateById(any(TaskEntity.class))).thenReturn(1);

        // null 是「这次不动日期」，空串才是「清空」——否则日期就成了只能改不能删的字段
        TaskDto untouched = service.patch("t1", new PatchTaskRequest(null, null, null, null, null, null, null), "wg");
        assertThat(untouched.start()).isEqualTo("2026-07-20");
        assertThat(untouched.due()).isEqualTo("2026-07-24");

        TaskDto cleared = service.patch("t1", new PatchTaskRequest(null, null, null, null, null, "", null), "wg");

        assertThat(cleared.start()).isEmpty();
        assertThat(cleared.due()).isEqualTo("2026-07-24");
        assertThat(cleared.activity()).extracting(cn.wayuwa.wao.dto.Activity::text).contains("清空了开始日期");
    }

    @Test
    void chatCollectedTaskCarriesTheSourceTag() {
        when(mapper.insert(any(TaskEntity.class))).thenReturn(1);

        TaskDto dto = service.createFromChat("p1", "语音里冒出来的想法", "语音里冒出来的想法，完整一段。",
                "wd", "群聊快速收集");

        assertThat(dto.status()).isEqualTo("待办");
        assertThat(dto.owner()).isEqualTo("wd");
        assertThat(dto.custom()).extracting(cn.wayuwa.wao.dto.CustomProp::value).containsExactly("群聊快速收集");
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
    void createRecordsCreatorSeparatelyFromOwner() {
        when(mapper.insert(any(TaskEntity.class))).thenReturn(1);

        TaskDto dto = service.create(new CreateTaskRequest("整理证件盒", "p2", "P1", "wd", "2026-07-30", null, null), "wg");

        assertThat(dto.owner()).isEqualTo("wd");
        assertThat(dto.createdBy()).isEqualTo("wg");
    }

    @Test
    void getThrowsWhenMissing() {
        when(mapper.selectById("nope")).thenReturn(null);
        assertThatThrownBy(() -> service.get("nope")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void listHidesTasksFromProjectsTheMemberCannotAccess() {
        TaskEntity visible = todo();
        TaskEntity hidden = todo();
        hidden.setId("t2");
        hidden.setProjectId("private-project");
        when(mapper.selectList(null)).thenReturn(List.of(visible, hidden));
        when(projects.get("p1", "wg")).thenReturn(null);
        when(projects.get("private-project", "wg"))
                .thenThrow(new NotFoundException("项目不存在：private-project"));

        assertThat(service.list("wg")).extracting(TaskDto::id).containsExactly("t1");
    }

    @Test
    void getDoesNotRevealTaskFromAnInvisibleProject() {
        when(mapper.selectById("t1")).thenReturn(todo());
        when(projects.get("p1", "wd")).thenThrow(new NotFoundException("项目不存在：p1"));

        assertThatThrownBy(() -> service.get("t1", "wd"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("任务不存在");
    }

    /** 一条已有评论的任务，detail 里带 id。 */
    private TaskEntity withComment() {
        TaskEntity e = todo();
        e.setDetailJson("{\"comments\":[{\"id\":\"c1\",\"who\":\"wg\",\"time\":\"2026-07-25T10:00:00+08:00\","
                + "\"text\":\"原始内容\"}],\"activity\":[]}");
        return e;
    }

    @Test
    void updateCommentRewritesTextAndKeepsAuthorAndTime() {
        TaskEntity e = withComment();
        when(mapper.selectById("t1")).thenReturn(e);
        when(mapper.updateById(any(TaskEntity.class))).thenReturn(1);

        var updated = service.updateComment("t1", "c1", "wg", "  改过的内容  ");

        assertThat(updated.text()).isEqualTo("改过的内容");
        assertThat(updated.id()).isEqualTo("c1");
        assertThat(updated.who()).isEqualTo("wg");
        assertThat(updated.time()).isEqualTo("2026-07-25T10:00:00+08:00");
        assertThat(service.get("t1").activity().get(0).text()).contains("修改了评论");
    }

    @Test
    void deleteCommentRemovesIt() {
        TaskEntity e = withComment();
        when(mapper.selectById("t1")).thenReturn(e);
        when(mapper.updateById(any(TaskEntity.class))).thenReturn(1);

        service.deleteComment("t1", "c1", "wg");

        assertThat(service.get("t1").comments()).isEmpty();
    }

    @Test
    void cannotTouchSomeoneElsesComment() {
        when(mapper.selectById("t1")).thenReturn(withComment());
        when(mapper.updateById(any(TaskEntity.class))).thenReturn(1);

        assertThatThrownBy(() -> service.updateComment("t1", "c1", "wd", "偷改"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("自己的评论");
        assertThatThrownBy(() -> service.deleteComment("t1", "c1", "wd"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void legacyCommentWithoutIdIsAddressedByIndex() {
        TaskEntity e = todo();
        e.setDetailJson("{\"comments\":[{\"who\":\"wg\",\"time\":\"昨天 21:40\",\"text\":\"老评论\"}],\"activity\":[]}");
        when(mapper.selectById("t1")).thenReturn(e);
        when(mapper.updateById(any(TaskEntity.class))).thenReturn(1);

        var updated = service.updateComment("t1", "0", "wg", "补充说明");

        assertThat(updated.text()).isEqualTo("补充说明");
    }

    @Test
    void updateCommentRejectsBlankText() {
        when(mapper.selectById("t1")).thenReturn(withComment());

        assertThatThrownBy(() -> service.updateComment("t1", "c1", "wg", "   "))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void deleteRemovesTheTask() {
        when(mapper.selectById("t1")).thenReturn(todo());

        when(projects.get("p1", "wg")).thenReturn(null);
        service.delete("t1", "wg");

        verify(mapper).deleteById("t1");
    }

    @Test
    void deleteThrowsWhenMissing() {
        when(mapper.selectById("nope")).thenReturn(null);
        assertThatThrownBy(() -> service.delete("nope", "wg")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void addCommentPersistsAndNotifiesFeishu() {
        when(mapper.selectById("t1")).thenReturn(todo());
        when(mapper.updateById(any(TaskEntity.class))).thenReturn(1);
        when(members.list()).thenReturn(List.of(new MemberDto("wg", "娃哥", "哥", "#4358D6", "管理员")));
        when(projects.get("p1")).thenThrow(new NotFoundException("项目不存在"));

        var comment = service.addComment("t1", "wg", "  已验收  ");

        assertThat(comment.text()).isEqualTo("已验收");
        assertThat(comment.time()).endsWith("+08:00");
        verify(notifier).notifyComment(any(TaskDto.class), eq(comment), eq("娃哥"), eq("p1"));
    }

    /**
     * 活动时间线记的是真实时刻和真实操作人。
     *
     * <p>原来一律写「你……」和「刚刚」：娃哥看娃弟改的记录也显示成「你」，
     * 而且过多久都是「刚刚」——两条都是用户报的 bug。
     */
    @Test
    void activityCarriesTheRealActorAndTimestamp() {
        when(mapper.selectById("t1")).thenReturn(todo());
        when(mapper.updateById(any(TaskEntity.class))).thenReturn(1);

        TaskDto dto = service.patch("t1", new PatchTaskRequest(null, "进行中", null, null, null, null, null), "wd");

        var latest = dto.activity().get(0);
        assertThat(latest.text()).isEqualTo("将状态改为「进行中」").doesNotContain("你");
        assertThat(latest.by()).isEqualTo("wd");
        assertThat(latest.time()).matches("\\d{4}-\\d{2}-\\d{2}T.*").endsWith("+08:00");
    }

    /** 新建任务的第一条活动同样记下创建人，而不是含糊的「你创建了任务」。 */
    @Test
    void createdActivityNamesTheCreator() {
        when(mapper.insert(any(TaskEntity.class))).thenReturn(1);

        TaskDto dto = service.create(new CreateTaskRequest("整理证件盒", "p2", "P1", "wd", "2026-07-30", null, null), "wg");

        assertThat(dto.activity().get(0).text()).isEqualTo("创建了任务");
        assertThat(dto.activity().get(0).by()).isEqualTo("wg");
    }

    /** 娃哥往娃弟建的任务里加子任务：通知要带上娃哥，并 @ 到娃弟。 */
    @Test
    void newSubtasksNotifyTheCreatorAndOwnerButNotTheOperator() {
        TaskEntity previous = todo();
        previous.setCreatedBy("wd");
        previous.setOwnerId("wd");
        when(mapper.selectById("t1")).thenReturn(previous);
        when(mapper.updateById(any(TaskEntity.class))).thenReturn(1);
        when(projects.get("p1")).thenThrow(new NotFoundException("项目不存在：p1"));
        when(projects.get("p1", "wg")).thenReturn(null);
        when(members.list()).thenReturn(List.of(
                new MemberDto("wg", "娃哥", "哥", "#4358D6", "管理员"),
                new MemberDto("wd", "娃弟", "弟", "#2E9E6B", "成员")));
        when(taskNotifier.feishuOpenId("wd")).thenReturn("ou_wd");

        service.replace("t1", dtoWithSubs(List.of(new cn.wayuwa.wao.dto.Step("扫描身份证", false, "s1", "", List.of()))), "wg");

        verify(taskNotifier).notifySubtasksAdded("t1", "p1", "设计任务详情抽屉交互稿", "p1",
                List.of("扫描身份证"), "娃哥", "娃弟", List.of("ou_wd"));
    }

    /** 任务分组跟着任务一起写透，局部更新别的字段时不会被抹掉。 */
    @Test
    void taskGroupSurvivesReplaceAndPatch() {
        TaskEntity entity = todo();
        when(mapper.selectById("t1")).thenReturn(entity);
        when(mapper.updateById(any(TaskEntity.class))).thenReturn(1);
        when(projects.get("p1")).thenThrow(new NotFoundException("项目不存在：p1"));
        when(projects.get("p1", "wg")).thenReturn(null);

        TaskDto saved = service.replace("t1", dtoWithGroup("tg-domain"), "wg");
        assertThat(saved.groupId()).isEqualTo("tg-domain");

        // 改状态这类局部更新不该顺手把分组抹掉
        entity.setDetailJson("{\"activity\":[],\"groupId\":\"tg-domain\"}");
        TaskDto patched = service.patch("t1", new PatchTaskRequest(null, "进行中", null, null, null, null, null), "wg");
        assertThat(patched.groupId()).isEqualTo("tg-domain");
    }

    private TaskDto dtoWithSubs(List<cn.wayuwa.wao.dto.Step> subs) {
        return new TaskDto("t1", "设计任务详情抽屉交互稿", "p1", "待办", "P0", "wd", List.of(), null,
                "2026-07-24", null, "desc", List.of(), subs, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), "wd", null, null);
    }

    private TaskDto dtoWithGroup(String groupId) {
        return new TaskDto("t1", "设计任务详情抽屉交互稿", "p1", "待办", "P0", "wg", List.of(), null,
                "2026-07-24", null, "desc", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), "wg", null, groupId);
    }
}
