package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import cn.wayuwa.wao.domain.DocEntity;
import cn.wayuwa.wao.domain.ProjectEntity;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.Activity;
import cn.wayuwa.wao.dto.MemberDto;
import cn.wayuwa.wao.mapper.DocMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.wayuwa.wao.mapper.TaskMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import static org.mockito.Mockito.verify;

class ProjectActivityServiceTest {

    private final TaskMapper taskMapper = mock(TaskMapper.class);
    private final DocMapper docMapper = mock(DocMapper.class);
    private final MemberService members = mock(MemberService.class);
    private final ProjectActivityService service =
            new ProjectActivityService(taskMapper, new ObjectMapper(), docMapper, members);

    // 2026-07-01 08:00:00 +08:00
    private static final long T0 = 1_782_864_000_000L;

    private ProjectEntity project(String id) {
        ProjectEntity p = new ProjectEntity();
        p.setId(id);
        p.setOwnerId("wg");
        return p;
    }

    private void stubMembers() {
        when(members.list()).thenReturn(List.of(
                new MemberDto("wg", "娃哥", "哥", "#333", "管理员"),
                new MemberDto("wd", "娃弟", "弟", "#666", "成员")));
    }

    @Test
    void aggregatesCreationCompletionAndDocEventsNewestFirst() {
        stubMembers();
        ProjectEntity p = project("p" + T0);

        TaskEntity task = new TaskEntity();
        task.setId("t" + (T0 + 60_000));
        task.setProjectId(p.getId());
        task.setTitle("铺电线");
        task.setCreatedBy("wd");
        task.setCompletedAt(LocalDateTime.of(2026, 7, 3, 10, 0));
        when(taskMapper.selectList(any())).thenReturn(List.of(task));

        DocEntity doc = new DocEntity();
        doc.setId("d" + (T0 + 120_000));
        doc.setKbId("kb-independent");
        task.setDetailJson("{\"subs\":[{\"docs\":[\"" + doc.getId() + "\"]}]}");
        doc.setTitle("走线记录");
        doc.setAuthorId("auto");
        doc.setCreatedAt(LocalDateTime.of(2026, 7, 2, 9, 0));
        when(docMapper.selectList(any())).thenReturn(List.of(doc));

        List<Activity> acts = service.forProject(p);

        assertThat(acts).hasSize(4);
        // 倒序：任务完成(7-3) > 文档创建(7-2) > 任务创建(7-1 08:01) > 项目创建(7-1 08:00)
        assertThat(acts.get(0).text()).isEqualTo("任务「铺电线」已完成");
        assertThat(acts.get(1).text()).isEqualTo("自动化 创建了文档「走线记录」");
        assertThat(acts.get(2).text()).isEqualTo("娃弟 创建了任务「铺电线」");
        assertThat(acts.get(3).text()).isEqualTo("娃哥 创建了项目");
        // 时间是 ISO 时间戳，前端 relativeTime 才能转成「x 分钟前」
        assertThat(acts.get(3).time()).startsWith("2026-07-01T08:00");
        assertThat(acts.get(0).time()).startsWith("2026-07-03T10:00");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<TaskEntity>> taskQuery = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(taskMapper).selectList(taskQuery.capture());
        assertThat(taskQuery.getValue().getSqlSelect())
                .isEqualTo("id,project_id,title,owner_id,created_by,completed_at,detail_json");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<DocEntity>> docQuery = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(docMapper).selectList(docQuery.capture());
        assertThat(docQuery.getValue().getSqlSelect())
                .isEqualTo("id,kb_id,title,author_id,created_at");
    }

    @Test
    void seedIdsWithoutTimestampProduceNoFabricatedEvents() {
        stubMembers();
        when(taskMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.forProject(project("p1"))).isEmpty();
    }

    @Test
    void legacyTaskWithoutCreatorFallsBackToOwnerName() {
        stubMembers();
        ProjectEntity p = project("p1");
        TaskEntity task = new TaskEntity();
        task.setId("t" + T0);
        task.setProjectId("p1");
        task.setTitle("老任务");
        task.setOwnerId("wg");
        when(taskMapper.selectList(any())).thenReturn(List.of(task));

        List<Activity> acts = service.forProject(p);

        assertThat(acts).hasSize(1);
        assertThat(acts.get(0).text()).isEqualTo("娃哥 创建了任务「老任务」");
    }
}
