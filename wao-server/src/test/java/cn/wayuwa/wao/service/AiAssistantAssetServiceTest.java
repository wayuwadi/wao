package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import cn.wayuwa.wao.domain.DocEntity;
import cn.wayuwa.wao.domain.KbEntity;
import cn.wayuwa.wao.domain.PersonalMemoryEntity;
import cn.wayuwa.wao.domain.ProjectEntity;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.AssistantSourceFilter;
import cn.wayuwa.wao.dto.AssistantSourceOptionsDto;
import cn.wayuwa.wao.mapper.DocMapper;
import cn.wayuwa.wao.mapper.KbMapper;
import cn.wayuwa.wao.mapper.PersonalMemoryMapper;
import cn.wayuwa.wao.mapper.ProjectMapper;
import cn.wayuwa.wao.mapper.TaskMapper;
import cn.wayuwa.wao.service.AiAssistantAssetService.TaskMiningContext;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AiAssistantAssetServiceTest {

    private final SettingService settings = mock(SettingService.class);
    private final DocMapper docMapper = mock(DocMapper.class);
    private final KbMapper kbMapper = mock(KbMapper.class);
    private final ProjectMapper projectMapper = mock(ProjectMapper.class);
    private final TaskMapper taskMapper = mock(TaskMapper.class);
    private final PersonalMemoryMapper memoryMapper = mock(PersonalMemoryMapper.class);
    private final AiAssistantAssetService service = new AiAssistantAssetService(
            settings, docMapper, kbMapper, projectMapper, taskMapper, memoryMapper, new ObjectMapper());

    @BeforeEach
    void emptyCollectionsByDefault() {
        when(docMapper.selectList(any())).thenReturn(List.of());
        when(kbMapper.selectList(any())).thenReturn(List.of());
        when(projectMapper.selectList(any())).thenReturn(List.of());
        when(taskMapper.selectList(any())).thenReturn(List.of());
        when(memoryMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void includesOnlyCurrentMembersPersonalMemoryInPageAssistantContext() {
        PersonalMemoryEntity memory = new PersonalMemoryEntity();
        memory.setId("mem1");
        memory.setMemberId("wg");
        memory.setTitle("晨跑偏好");
        memory.setContent("喜欢日出前安静地跑步");
        memory.setCategory("preference");
        memory.setCaptureMode("manual");
        memory.setMediaType("text");
        memory.setUpdatedAt(LocalDateTime.now());
        when(memoryMapper.selectList(any())).thenReturn(List.of(memory));
        AssistantSourceFilter filter = new AssistantSourceFilter(
                List.of("memory"), List.of(), List.of(), null, null, List.of());

        String own = service.relevantAssets("晨跑偏好", filter, "wg");
        String organizationJob = service.relevantAssets("晨跑偏好", filter);

        assertThat(own).contains("type=\"memory\"", "喜欢日出前安静地跑步", "/memory?entry=mem1");
        assertThat(organizationJob).isEmpty();
    }

    @Test
    void filtersOldChatRecordsByGroupSenderAndDate() {
        when(settings.get(SettingService.CHAT_SYNC_CHAT_ID, "")).thenReturn("oc_chat");
        when(settings.get(SettingService.CHAT_SYNC_CHAT_NAME, "")).thenReturn("WAO 项目群");
        when(settings.get(SettingService.CHAT_SYNC_KB_ID, "")).thenReturn("kb-chat");
        DocEntity july = doc("d1", "kb-chat", "飞书群聊 · 2026-07-02",
                "[{\"p\":\"**09:10 娃哥**：十一月开始预算\"},{\"p\":\"**09:12 娃弟**：收到\"}]");
        DocEntity august = doc("d2", "kb-chat", "飞书群聊 · 2026-08-03",
                "[{\"p\":\"**10:10 娃哥**：八月的新安排\"}]");
        when(docMapper.selectList(any())).thenReturn(List.of(august, july));
        AssistantSourceFilter filter = new AssistantSourceFilter(
                List.of("chat"), List.of("oc_chat"), List.of("娃哥"),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), List.of());

        String context = service.relevantAssets("之前预算怎么安排？", filter);

        assertThat(context).contains("WAO 项目群")
                .contains("飞书群聊 · 2026-07-02")
                .contains("十一月开始预算")
                .contains("/kb/kb-chat?doc=d1")
                .doesNotContain("娃弟")
                .doesNotContain("2026-08-03");
    }

    @Test
    void searchesKnowledgeProjectsAndTasksAsTraceableAssets() {
        KbEntity kb = new KbEntity();
        kb.setId("kb1");
        kb.setName("发布手册");
        kb.setProjectId("p1");
        when(kbMapper.selectList(any())).thenReturn(List.of(kb));
        when(docMapper.selectList(any())).thenReturn(List.of(doc("doc1", "kb1", "上线检查",
                "[{\"p\":\"发布前检查数据库迁移。\"}]")));
        ProjectEntity project = new ProjectEntity();
        project.setId("p1");
        project.setName("WAO 发布项目");
        project.setGoal("稳定上线");
        when(projectMapper.selectList(any())).thenReturn(List.of(project));
        TaskEntity task = new TaskEntity();
        task.setId("t1");
        task.setProjectId("p1");
        task.setTitle("发布检查任务");
        task.setDescription("确认数据库迁移");
        task.setDetailJson("{\"docs\":[\"doc1\"]}");
        when(taskMapper.selectList(any())).thenReturn(List.of(task));

        String context = service.relevantAssets("发布前数据库迁移任务", new AssistantSourceFilter(
                List.of("knowledge", "project", "task"), List.of(), List.of(), null, null, List.of("p1")));

        assertThat(context).contains("type=\"knowledge\"").contains("/kb/kb1?doc=doc1")
                .contains("type=\"project\"").contains("/projects/p1")
                .contains("type=\"task\"").contains("/projects/p1?task=t1")
                .contains("参考 WAO 资产");
    }

    @Test
    void projectKnowledgeFilterIncludesOnlyExplicitTaskAndSubtaskDocuments() {
        KbEntity legacy = new KbEntity(); legacy.setId("kb-legacy"); legacy.setName("旧项目库"); legacy.setProjectId("p1");
        KbEntity independent = new KbEntity(); independent.setId("kb-independent"); independent.setName("独立库");
        when(kbMapper.selectList(any())).thenReturn(List.of(legacy, independent));
        when(docMapper.selectList(any())).thenReturn(List.of(
                doc("unlinked", "kb-legacy", "发布秘密资料", "[{\"p\":\"发布秘密资料\"}]"),
                doc("linked", "kb-independent", "发布任务资料", "[{\"p\":\"发布任务资料\"}]")));
        TaskEntity task = new TaskEntity(); task.setProjectId("p1");
        task.setDetailJson("{\"subs\":[{\"docs\":[\"linked\"]}]}");
        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        String context = service.relevantAssets("发布", new AssistantSourceFilter(
                List.of("knowledge"), List.of(), List.of(), null, null, List.of("p1")));
        assertThat(context).contains("发布任务资料").doesNotContain("发布秘密资料");
    }

    @Test
    void returnsRealChatAndSenderDateOptions() {
        when(settings.get(SettingService.CHAT_SYNC_CHAT_ID, "")).thenReturn("oc_chat");
        when(settings.get(SettingService.CHAT_SYNC_CHAT_NAME, "")).thenReturn("WAO 项目群");
        when(settings.get(SettingService.CHAT_SYNC_KB_ID, "")).thenReturn("kb-chat");
        when(docMapper.selectList(any())).thenReturn(List.of(
                doc("d1", "kb-chat", "飞书群聊 · 2026-06-01", "[{\"p\":\"**09:10 娃哥**：早\"}]"),
                doc("d2", "kb-chat", "飞书群聊 · 2026-08-10", "[{\"p\":\"**10:10 娃弟**：晚\"}]")));

        AssistantSourceOptionsDto options = service.sourceOptions();

        assertThat(options.chats()).singleElement().satisfies(chat -> {
            assertThat(chat.id()).isEqualTo("oc_chat");
            assertThat(chat.name()).isEqualTo("WAO 项目群");
        });
        assertThat(options.senders()).containsExactly("娃哥", "娃弟");
        assertThat(options.earliestChatDate()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(options.latestChatDate()).isEqualTo(LocalDate.of(2026, 8, 10));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<DocEntity>> docQueries = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(docMapper, times(3)).selectList(docQueries.capture());
        assertThat(docQueries.getAllValues().get(0).getSqlSelect())
                .contains("id", "kb_id", "title", "blocks_json", "updated_at")
                .doesNotContain("links_json", "author_id");
        assertThat(docQueries.getAllValues())
                .allSatisfy(query -> assertThat(query.getCustomSqlSegment()).contains("archived_at IS NULL"));
        assertThat(docQueries.getAllValues().get(1).getSqlSelect()).isEqualTo("title");
        assertThat(docQueries.getAllValues().get(2).getSqlSelect()).isEqualTo("title");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<ProjectEntity>> projectQuery =
                ArgumentCaptor.forClass(QueryWrapper.class);
        verify(projectMapper).selectList(projectQuery.capture());
        assertThat(projectQuery.getValue().getSqlSelect()).contains("id", "name").doesNotContain("detail_json");
    }

    @Test
    void taskMiningContextUsesOnlyRealChatAndKnowledgeSourcesAndOpenTaskTitles() {
        when(settings.get(SettingService.CHAT_SYNC_KB_ID, "")).thenReturn("kb-chat");
        KbEntity kb = new KbEntity();
        kb.setId("kb1");
        kb.setName("产品想法");
        when(kbMapper.selectList(any())).thenReturn(List.of(kb));
        DocEntity chat = doc("d-chat", "kb-chat", "飞书群聊 · 2026-08-12",
                "[{\"p\":\"**10:10 娃哥**：任务等待时需要明确原因\"}]");
        DocEntity knowledge = doc("d-kb", "kb1", "移动端待办想法",
                "[{\"p\":\"希望手机上可以快速筛选今天的任务。\"}]");
        when(docMapper.selectList(any())).thenReturn(List.of(chat), List.of(knowledge));
        TaskEntity open = new TaskEntity();
        open.setId("t-open");
        open.setTitle("已有未完成任务");
        when(taskMapper.selectList(any())).thenReturn(List.of(open));

        TaskMiningContext context = service.taskMiningContext();

        assertThat(context.sources()).hasSize(2)
                .extracting(source -> source.type()).containsExactlyInAnyOrder("chat", "knowledge");
        assertThat(context.promptContext()).contains("任务等待时需要明确原因")
                .contains("手机上可以快速筛选今天的任务")
                .contains("/kb/kb-chat?doc=d-chat")
                .contains("/kb/kb1?doc=d-kb");
        assertThat(context.openTaskTitles()).containsExactly("已有未完成任务");
    }

    private DocEntity doc(String id, String kbId, String title, String blocks) {
        DocEntity doc = new DocEntity();
        doc.setId(id);
        doc.setKbId(kbId);
        doc.setTitle(title);
        doc.setAuto(true);
        doc.setBlocksJson(blocks);
        doc.setUpdatedAt(LocalDateTime.now());
        return doc;
    }
}
