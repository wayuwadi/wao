package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

import java.util.List;
import java.util.Map;

import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.ProjectEntity;
import cn.wayuwa.wao.dto.Activity;
import cn.wayuwa.wao.dto.CreateProjectRequest;
import cn.wayuwa.wao.dto.PatchProjectRequest;
import cn.wayuwa.wao.dto.ProjectDto;
import cn.wayuwa.wao.mapper.ProjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ProjectServiceTest {

    private final ProjectMapper mapper = mock(ProjectMapper.class);
    private final ProjectActivityService activities = mock(ProjectActivityService.class);
    private final FeishuTaskNotifier notifier = mock(FeishuTaskNotifier.class);
    private final MemberService members = mock(MemberService.class);
    private final ProjectService service = new ProjectService(mapper, new ObjectMapper(), notifier, members, activities);

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
    void ideaCaptureCanCreateSilently() {
        service.create(new CreateProjectRequest("静默点子", "career", "最终目标", true, false), "wg");

        verify(notifier, never()).notifyProjectCreated(any(), any(), any(), any());
    }

    @Test
    void startMovesProjectAndNotifiesOnlyOnce() {
        ProjectEntity entity = project("p-start", "wg", "[\"wg\"]");
        entity.setStatus("规划中");
        when(mapper.selectByIdForUpdate("p-start")).thenReturn(entity);
        when(members.list()).thenReturn(List.of());

        ProjectDto first = service.start("p-start", "wg");
        ProjectDto second = service.start("p-start", "wg");

        assertThat(first.status()).isEqualTo("进行中");
        assertThat(second.status()).isEqualTo("进行中");
        verify(notifier).notifyProjectStarted("p-start", "项目 p-start", "wg", null);
    }

    @Test
    void startDoesNotReopenCompletedOrShelvedProject() {
        ProjectEntity entity = project("p-closed", "wg", "[\"wg\"]");
        entity.setStatus("已搁置");
        when(mapper.selectByIdForUpdate("p-closed")).thenReturn(entity);

        ProjectDto result = service.start("p-closed", "wg");

        assertThat(result.status()).isEqualTo("已搁置");
        verify(mapper, never()).updateById(any(ProjectEntity.class));
        verify(notifier, never()).notifyProjectStarted(any(), any(), any(), any());
    }

    @Test
    void notificationSwitchCanBeTurnedOffAndBackOn() {
        ProjectEntity e = new ProjectEntity();
        e.setId("p2");
        e.setName("家庭资料归档");
        e.setOwnerId("wg");
        e.setNotifyEnabled(true);
        when(mapper.selectById("p2")).thenReturn(e);
        when(mapper.updateById(any(ProjectEntity.class))).thenReturn(1);

        assertThat(service.update("p2", new PatchProjectRequest(null, null, null, null, false), "wg").notifyEnabled())
                .isFalse();
        assertThat(service.update("p2", new PatchProjectRequest(null, null, null, null, true), "wg").notifyEnabled())
                .isTrue();
        // 不带这个字段的更新（改名、换图标）不该顺手把开关重置掉
        assertThat(service.update("p2", new PatchProjectRequest("改个名字", null, null, null, null), "wg").notifyEnabled())
                .isTrue();
    }

    @Test
    void projectStatusCanBeMaintainedAndRejectsUnknownValues() {
        ProjectEntity e = project("p-status", "wg", "[\"wg\"]");
        e.setStatus("规划中");
        when(mapper.selectById("p-status")).thenReturn(e);

        ProjectDto updated = service.update("p-status",
                new PatchProjectRequest(null, null, "进行中", null, null, null, null), "wg");

        assertThat(updated.status()).isEqualTo("进行中");
        assertThat(service.update("p-status",
                new PatchProjectRequest(null, null, "已搁置", null, null, null, null), "wg").status())
                .isEqualTo("已搁置");
        assertThatThrownBy(() -> service.update("p-status",
                new PatchProjectRequest(null, null, "随便写", null, null, null, null), "wg"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("不支持的项目状态");
    }

    @Test
    void legacyProjectsWithoutTheColumnCountAsNotifying() {
        ProjectEntity e = new ProjectEntity();
        e.setId("p3");
        e.setName("健康重启计划");
        when(mapper.selectById("p3")).thenReturn(e);

        assertThat(service.get("p3").notifyEnabled()).isTrue();
    }

    // ---------------- 可见性隔离 ----------------

    private static ProjectEntity project(String id, String owner, String membersJson) {
        ProjectEntity e = new ProjectEntity();
        e.setId(id);
        e.setName("项目 " + id);
        e.setOwnerId(owner);
        e.setDetailJson(membersJson == null ? null : "{\"members\":" + membersJson + "}");
        return e;
    }

    @Test
    void listOnlyShowsProjectsTheMemberOwnsOrJoined() {
        ProjectEntity shared = project("pa", "wg", "[\"wg\",\"wd\"]");
        ProjectEntity wgOnly = project("pb", "wg", "[\"wg\"]");
        ProjectEntity wdOnly = project("pc", "wd", "[\"wd\"]");
        when(mapper.selectList(null)).thenReturn(List.of(shared, wgOnly, wdOnly));
        when(activities.forProjects(anyCollection())).thenReturn(Map.of());

        assertThat(service.list("wd").stream().map(ProjectDto::id)).containsExactly("pa", "pc");
        assertThat(service.list("wg").stream().map(ProjectDto::id)).containsExactly("pa", "pb");
    }

    @Test
    void visibleProjectIdsUsesTheSameAccessRulesWithoutLoadingActivities() {
        ProjectEntity shared = project("pa", "wg", "[\"wg\",\"wd\"]");
        ProjectEntity privateProject = project("pb", "wg", "[\"wg\"]");
        when(mapper.selectList(null)).thenReturn(List.of(shared, privateProject));

        assertThat(service.visibleProjectIds("wd")).containsExactly("pa");
        verify(activities, never()).forProjects(anyCollection());
    }

    @Test
    void personalProjectOfSomeoneElseIsInvisibleEvenById() {
        when(mapper.selectById("pc")).thenReturn(project("pc", "wd", "[\"wd\"]"));

        assertThatThrownBy(() -> service.get("pc", "wg")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.update("pc", new PatchProjectRequest("改名", null, null, null, null), "wg"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.delete("pc", "wg")).isInstanceOf(NotFoundException.class);
        assertThat(service.get("pc", "wd").id()).isEqualTo("pc");
    }

    // ---------------- 动态时间线 ----------------

    @Test
    void dtoPrefersLiveActivityOverStoredSnapshot() {
        ProjectEntity e = project("p9", "wg", "[\"wg\"]");
        e.setDetailJson("{\"members\":[\"wg\"],\"activity\":[{\"icon\":\"ri-add-line\",\"color\":\"#9A9DA9\","
                + "\"text\":\"你创建了项目\",\"time\":\"刚刚\"}]}");
        when(mapper.selectById("p9")).thenReturn(e);
        when(activities.forProject(e)).thenReturn(
                List.of(new Activity("ri-add-line", "#9A9DA9", "娃哥 创建了项目", "2026-07-01T08:00:00+08:00")));

        assertThat(service.get("p9").activity()).hasSize(1);
        assertThat(service.get("p9").activity().get(0).text()).isEqualTo("娃哥 创建了项目");
    }

    @Test
    void dtoFallsBackToStoredActivityWhenNothingCanBeComputed() {
        ProjectEntity e = project("p9", "wg", "[\"wg\"]");
        e.setDetailJson("{\"members\":[\"wg\"],\"activity\":[{\"icon\":\"ri-add-line\",\"color\":\"#9A9DA9\","
                + "\"text\":\"你创建了项目\",\"time\":\"刚刚\"}]}");
        when(mapper.selectById("p9")).thenReturn(e);
        when(activities.forProject(e)).thenReturn(List.of());

        assertThat(service.get("p9").activity().get(0).text()).isEqualTo("你创建了项目");
    }

    // ---------------- 任务分组 ----------------

    @Test
    void taskGroupsAreTrimmedDeduplicatedAndCappedAtSix() {
        ProjectEntity e = project("p1", "wg", "[\"wg\"]");
        when(mapper.selectById("p1")).thenReturn(e);

        List<cn.wayuwa.wao.dto.TaskGroupDto> requested = List.of(
                new cn.wayuwa.wao.dto.TaskGroupDto("g1", "  证件  "),
                new cn.wayuwa.wao.dto.TaskGroupDto("g1", "重复 id 会被丢掉"),
                new cn.wayuwa.wao.dto.TaskGroupDto("g2", "   "),
                new cn.wayuwa.wao.dto.TaskGroupDto("g3", "装修"),
                new cn.wayuwa.wao.dto.TaskGroupDto("g4", "上线前"),
                new cn.wayuwa.wao.dto.TaskGroupDto("g5", "备份"),
                new cn.wayuwa.wao.dto.TaskGroupDto("g6", "杂事"),
                new cn.wayuwa.wao.dto.TaskGroupDto("g7", "第六个之后的"),
                new cn.wayuwa.wao.dto.TaskGroupDto("g8", "更多"));

        ProjectDto dto = service.update("p1",
                new PatchProjectRequest(null, null, null, null, null, requested), "wg");

        assertThat(dto.taskGroups()).hasSize(ProjectService.MAX_TASK_GROUPS);
        assertThat(dto.taskGroups().get(0).name()).isEqualTo("证件");
        assertThat(dto.taskGroups()).extracting(cn.wayuwa.wao.dto.TaskGroupDto::id)
                .containsExactly("g1", "g3", "g4", "g5", "g6", "g7");
    }

    @Test
    void patchingOtherFieldsKeepsExistingTaskGroups() {
        ProjectEntity e = project("p1", "wg", "[\"wg\"]");
        e.setDetailJson("{\"members\":[\"wg\"],\"icon\":\"ri-stack-line\","
                + "\"taskGroups\":[{\"id\":\"g1\",\"name\":\"证件\"}]}");
        when(mapper.selectById("p1")).thenReturn(e);

        ProjectDto dto = service.update("p1", new PatchProjectRequest(null, null, null, "ri-home-line", null), "wg");

        assertThat(dto.icon()).isEqualTo("ri-home-line");
        assertThat(dto.taskGroups()).extracting(cn.wayuwa.wao.dto.TaskGroupDto::name).containsExactly("证件");
    }
}
