package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.DocEntity;
import cn.wayuwa.wao.domain.DocTemplateEntity;
import cn.wayuwa.wao.domain.KbEntity;
import cn.wayuwa.wao.domain.KbFolderEntity;
import cn.wayuwa.wao.domain.MemberEntity;
import cn.wayuwa.wao.dto.CreateDocRequest;
import cn.wayuwa.wao.dto.CreateDocTemplateRequest;
import cn.wayuwa.wao.dto.CreateKbRequest;
import cn.wayuwa.wao.dto.CreateNoteRequest;
import cn.wayuwa.wao.dto.DocBlockDto;
import cn.wayuwa.wao.dto.DocDto;
import cn.wayuwa.wao.dto.DocLinkDto;
import cn.wayuwa.wao.dto.DocTemplateDto;
import cn.wayuwa.wao.dto.KbDto;
import cn.wayuwa.wao.dto.IdeaDto;
import cn.wayuwa.wao.dto.PatchDocRequest;
import cn.wayuwa.wao.dto.PatchDocTemplateRequest;
import cn.wayuwa.wao.dto.PatchKbRequest;
import cn.wayuwa.wao.dto.ProjectDto;
import cn.wayuwa.wao.dto.ReorderDocsRequest;
import cn.wayuwa.wao.dto.RichDocContentDto;
import cn.wayuwa.wao.dto.TrashEntryDto;
import cn.wayuwa.wao.mapper.DocMapper;
import cn.wayuwa.wao.mapper.DocTemplateMapper;
import cn.wayuwa.wao.mapper.KbFolderMapper;
import cn.wayuwa.wao.mapper.KbGroupMapper;
import cn.wayuwa.wao.mapper.KbMapper;
import cn.wayuwa.wao.mapper.MemberMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class KnowledgeServiceTest {
    @Test
    void catalogOmitsOrdinaryBodiesButKeepsCompleteNotesWithoutPerDocumentQueries() {
        KbEntity notesKb = kb("notes");
        notesKb.setNotes(true);
        DocEntity ordinary = doc("d1", "k1", "f1");
        DocEntity note = doc("n1", "notes", "nf");
        note.setContentMarkdown("完整小记正文，用于全文搜索和编辑");
        note.setContentVersion(1);
        when(kbMapper.selectList(any())).thenReturn(List.of(notesKb));
        when(docMapper.selectList(any())).thenReturn(List.of(ordinary, note), List.of(note));

        List<DocDto> result = service.listDocCatalog();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).bodyLoaded()).isFalse();
        assertThat(result.get(0).content()).isNull();
        assertThat(result.get(0).blocks()).isEmpty();
        assertThat(result.get(0).title()).isEqualTo(ordinary.getTitle());
        assertThat(result.get(1).bodyLoaded()).isTrue();
        assertThat(result.get(1).content().markdown()).isEqualTo(note.getContentMarkdown());
        var queries = ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(docMapper, times(2)).selectList(queries.capture());
        assertThat(queries.getAllValues().get(0).getSqlSelect())
                .doesNotContain("blocks_json", "content_json", "content_markdown");
        assertThat(queries.getAllValues().get(0).getSqlSegment()).contains("deleted_at IS NULL", "archived_at IS NULL");
        verify(docMapper, never()).selectBacklinkSources(anyString());
    }

    @org.junit.jupiter.api.BeforeAll
    static void initializeMapperMetadata() {
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, DocEntity.class);
    }


    private final KbGroupMapper groupMapper = mock(KbGroupMapper.class);
    private final KbMapper kbMapper = mock(KbMapper.class);
    private final KbFolderMapper folderMapper = mock(KbFolderMapper.class);
    private final DocMapper docMapper = mock(DocMapper.class);
    private final DocTemplateMapper templateMapper = mock(DocTemplateMapper.class);
    private final MemberMapper memberMapper = mock(MemberMapper.class);
    private final FeishuTaskNotifier taskNotifier = mock(FeishuTaskNotifier.class);
    private final FeishuChatArchiveVersionService archiveVersions = mock(FeishuChatArchiveVersionService.class);
    private final KnowledgeService service = new KnowledgeService(groupMapper, kbMapper, folderMapper, docMapper,
            templateMapper, memberMapper, new ObjectMapper(), taskNotifier, archiveVersions);

    private MemberEntity member(String id, String name) {
        MemberEntity m = new MemberEntity();
        m.setId(id);
        m.setName(name);
        when(memberMapper.selectById(id)).thenReturn(m);
        return m;
    }

    private KbEntity kb(String id) {
        KbEntity kb = new KbEntity();
        kb.setId(id);
        kb.setName("知识库 " + id);
        return kb;
    }

    private KbFolderEntity folder(String id, String kbId) {
        KbFolderEntity f = new KbFolderEntity();
        f.setId(id);
        f.setKbId(kbId);
        f.setName("文件夹 " + id);
        return f;
    }

    private DocEntity doc(String id, String kbId, String folderId) {
        DocEntity d = new DocEntity();
        d.setId(id);
        d.setKbId(kbId);
        d.setFolderId(folderId);
        d.setTitle("文档 " + id);
        d.setBlocksJson("[]");
        d.setLinksJson("[]");
        d.setRev(1);
        return d;
    }

    @Test
    void listKbsReadsOnlyDocumentIdentityAndFolderOrder() {
        KbEntity kb = kb("k1");
        KbFolderEntity folder = folder("f1", kb.getId());
        DocEntity doc = doc("d1", kb.getId(), folder.getId());
        when(kbMapper.selectList(any())).thenReturn(List.of(kb));
        when(folderMapper.selectList(any())).thenReturn(List.of(folder));
        when(docMapper.selectList(any())).thenReturn(List.of(doc));

        assertThat(service.listKbs()).singleElement().satisfies(result ->
                assertThat(result.folders().get(0).docs()).containsExactly("d1"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<DocEntity>> query =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(docMapper).selectList(query.capture());
        assertThat(query.getValue().getSqlSelect()).isEqualTo("id,folder_id");
        assertThat(query.getValue().getCustomSqlSegment()).contains("archived_at IS NULL");
    }

    @Test
    void archiveKeepsDocumentAndRestoreReturnsItToTheOriginalFolder() {
        DocEntity document = doc("d1", "k1", "f1");
        when(docMapper.selectById("d1")).thenReturn(document);
        when(kbMapper.selectById("k1")).thenReturn(kb("k1"));
        when(folderMapper.selectById("f1")).thenReturn(folder("f1", "k1"));
        when(docMapper.markArchived(eq("d1"), any(LocalDateTime.class), eq("wg"))).thenReturn(1);
        when(docMapper.clearArchived("d1")).thenReturn(1);
        when(docMapper.selectList(any())).thenReturn(List.of(document));

        DocDto archived = service.archiveDoc("d1", "wg");

        assertThat(archived.id()).isEqualTo("d1");
        assertThat(document.getArchivedAt()).isNotNull();
        assertThat(document.getArchivedBy()).isEqualTo("wg");
        assertThat(document.getDeletedAt()).isNull();

        DocDto restored = service.unarchiveDoc("d1");

        assertThat(restored.folderId()).isEqualTo("f1");
        assertThat(document.getArchivedAt()).isNull();
        assertThat(document.getArchivedBy()).isNull();
        verify(docMapper).clearArchived("d1");
        verify(docMapper, never()).markDeleted(anyString(), any(LocalDateTime.class), anyString(), anyBoolean());
    }

    @Test
    void archivedListRequiresAnExistingKnowledgeBaseAndReturnsItsDocuments() {
        KbEntity knowledgeBase = kb("k1");
        DocEntity archived = doc("d-old", "k1", "f1");
        archived.setArchivedAt(LocalDateTime.of(2026, 9, 3, 10, 0));
        when(kbMapper.selectById("k1")).thenReturn(knowledgeBase);
        when(docMapper.selectList(any())).thenReturn(List.of(archived), List.of(archived));

        assertThat(service.listArchivedDocs("k1")).extracting(DocDto::id).containsExactly("d-old");
        verify(kbMapper).selectById("k1");
        verify(docMapper, times(2)).selectList(any());
    }

    @Test
    void olderChatSnapshotCannotOverwriteOrMasqueradeAsTheLatestArchive() {
        KbEntity kb = kb("kb-chat");
        KbFolderEntity folder = folder("f-chat", kb.getId());
        DocEntity latest = doc("d-chat", kb.getId(), folder.getId());
        latest.setTitle("飞书群聊 · 2026-08-19");
        latest.setAutoSyncScope("oc_chat");
        latest.setAutoSyncDate(LocalDate.of(2026, 8, 19));
        latest.setAutoSyncVersion(12L);
        when(kbMapper.selectByIdForUpdate(kb.getId())).thenReturn(kb);
        when(folderMapper.selectById(folder.getId())).thenReturn(folder);
        when(docMapper.selectList(any())).thenReturn(List.of(latest));
        when(docMapper.updateAutoArchive(latest.getId(), kb.getId(), folder.getId(), latest.getTitle(), "[]",
                "oc_chat", LocalDate.of(2026, 8, 19), 11L)).thenReturn(0);

        assertThatThrownBy(() -> service.upsertAutoDoc(
                kb.getId(), folder.getId(), latest.getTitle(), List.of(),
                "oc_chat", LocalDate.of(2026, 8, 19), 11L))
                .isInstanceOf(StaleChatArchiveException.class)
                .hasMessageContaining("旧快照");

        verify(docMapper, never()).updateById(any(DocEntity.class));
    }

    @Test
    void manualArchiveEditSignsANewerVersionAndUsesTheDedicatedCas() {
        LocalDate day = LocalDate.of(2026, 8, 19);
        DocEntity archive = doc("d-chat", "kb-chat", "f-chat");
        archive.setTitle("飞书群聊 · 2026-08-19");
        archive.setAuto(true);
        archive.setAutoSyncScope("oc_chat");
        archive.setAutoSyncDate(day);
        archive.setAutoSyncVersion(12L);
        when(docMapper.selectById(archive.getId())).thenReturn(archive);
        when(docMapper.selectList(any())).thenReturn(List.of(archive));
        when(archiveVersions.nextVersion()).thenReturn(13L);
        when(docMapper.updateAutoArchiveManually(eq(archive.getId()), eq("f-chat"),
                eq(archive.getTitle()), anyString(), anyString(), eq(2), eq("oc_chat"), eq(day),
                eq(12L), eq(13L))).thenAnswer(invocation -> {
                    archive.setBlocksJson(invocation.getArgument(3));
                    archive.setRev(invocation.getArgument(5));
                    archive.setAutoSyncVersion(invocation.getArgument(9));
                    return 1;
                });

        DocDto edited = service.updateDoc(archive.getId(), new PatchDocRequest(
                null, List.of(new DocBlockDto(null, null, "手工修订", null, null)), null, null));

        assertThat(edited.blocks()).extracting(DocBlockDto::p).containsExactly("手工修订");
        assertThat(archive.getAutoSyncVersion()).isEqualTo(13L);
        verify(docMapper, never()).updateById(any(DocEntity.class));
    }

    @Test
    void createDocAssociatesWithGivenKbAndFolder() {
        when(kbMapper.selectById("k1")).thenReturn(kb("k1"));
        when(folderMapper.selectById("f1")).thenReturn(folder("f1", "k1"));
        when(docMapper.selectCount(any())).thenReturn(0L);
        when(docMapper.selectList(any())).thenReturn(List.of());

        DocDto dto = service.createDoc(new CreateDocRequest("k1", "f1", "新文档"), "m-wadi");

        assertThat(dto.id()).startsWith("d");
        assertThat(dto.title()).isEqualTo("新文档");
        assertThat(dto.blocks()).isEmpty();
        assertThat(dto.kbId()).isEqualTo("k1");
        assertThat(dto.rev()).isEqualTo(1);
    }

    @Test
    void createDocImportsSuppliedArticleBlocksAtomically() {
        when(kbMapper.selectById("k1")).thenReturn(kb("k1"));
        when(folderMapper.selectById("f1")).thenReturn(folder("f1", "k1"));
        when(docMapper.selectCount(any())).thenReturn(0L);
        when(docMapper.selectList(any())).thenReturn(List.of());
        List<DocBlockDto> blocks = List.of(
                new DocBlockDto(null, "我的 WAO", null, null, null, 1),
                new DocBlockDto(null, null, "这是一篇已经完成的图文成稿。", null, null));

        DocDto dto = service.createDoc(new CreateDocRequest("k1", "f1", "我的 WAO", null, blocks), "m-wadi");

        assertThat(dto.blocks()).containsExactlyElementsOf(blocks);
        assertThat(dto.rev()).isEqualTo(1);
    }

    @Test
    void richDocumentPersistsJsonAndMarkdownAsOneVersionedSnapshot() throws Exception {
        when(kbMapper.selectById("k1")).thenReturn(kb("k1"));
        when(folderMapper.selectById("f1")).thenReturn(folder("f1", "k1"));
        when(docMapper.selectCount(any())).thenReturn(0L);
        when(docMapper.selectList(any())).thenReturn(List.of());
        RichDocContentDto rich = new RichDocContentDto(1, new ObjectMapper().readTree("""
                {"type":"doc","content":[{"type":"callout","attrs":{"kind":"warning","uid":"c1"},
                "content":[{"type":"paragraph","content":[{"type":"text","text":"当心"}]}]}]}
                """), "> [!WARNING]\n> 当心");

        DocDto dto = service.createDoc(new CreateDocRequest("k1", "f1", "高级文档", null,
                List.of(new DocBlockDto(null, null, "当心", null, null)), rich), "m1");

        ArgumentCaptor<DocEntity> inserted = ArgumentCaptor.forClass(DocEntity.class);
        verify(docMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getContentVersion()).isEqualTo(1);
        assertThat(inserted.getValue().getContentJson()).contains("callout");
        assertThat(inserted.getValue().getContentMarkdown()).isEqualTo("> [!WARNING]\n> 当心");
        assertThat(dto.content()).isEqualTo(rich);

        RichDocContentDto card = new RichDocContentDto(1, new ObjectMapper().readTree("""
                {"type":"doc","content":[{"type":"linkCard","attrs":{"uid":"l1","id":"","name":"示例",
                "size":"","mime":"","url":"http://example.com","title":"兼容链接","snapshot":""}}]}
                """), "[兼容链接](http://example.com)");
        assertThatCode(() -> service.createDoc(new CreateDocRequest("k1", "f1", "链接卡片", null,
                List.of(new DocBlockDto(null, null, "兼容链接", null, null)), card), "m1"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"1\"", "\"a\"", "\"A\"", "\"i\"", "\"I\""})
    void orderedListFromEditorSavesAndReloadsWithNumberingStyle(String style) throws Exception {
        DocEntity existing = doc("d-list", "k1", "f1");
        when(docMapper.selectById(existing.getId())).thenReturn(existing);
        when(docMapper.selectList(any())).thenReturn(List.of(existing));
        when(docMapper.updateIfRevision(any(), eq(1))).thenReturn(1);
        RichDocContentDto content = orderedListContent(style);

        DocDto saved = service.updateDoc(existing.getId(),
                new PatchDocRequest(null, List.of(), content, null, null, 1));

        assertThat(saved.rev()).isEqualTo(2);
        assertThat(saved.content()).isEqualTo(content);
        assertThat(new ObjectMapper().readTree(existing.getContentJson())).isEqualTo(content.document());
        assertThat(existing.getContentMarkdown()).isEqualTo("今日完成：\n\n1. 完成方案\n\n2. ");
        verify(docMapper).updateIfRevision(existing, 1);

        var template = service.createTemplate(new CreateDocTemplateRequest(
                "列表模板", "", existing.getId(), List.of(), content), "m1");
        assertThat(template.content()).isEqualTo(content);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"disc\"", "\"javascript:alert(1)\"", "1", "true", "{}", "[]"})
    void orderedListRejectsUnknownOrMalformedNumberingStyle(String style) throws Exception {
        DocEntity existing = doc("d-list", "k1", "f1");
        when(docMapper.selectById(existing.getId())).thenReturn(existing);

        assertThatThrownBy(() -> service.updateDoc(existing.getId(),
                new PatchDocRequest(null, null, orderedListContent(style), null, null, 1)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("编号样式不受支持");
        verify(docMapper, never()).updateIfRevision(any(), anyInt());
    }

    @Test
    void listTypeAttributeDoesNotBroadenOtherNodesOrMarks() throws Exception {
        DocEntity existing = doc("d-list", "k1", "f1");
        when(docMapper.selectById(existing.getId())).thenReturn(existing);
        for (String node : List.of(
                "{\"type\":\"paragraph\",\"attrs\":{\"type\":null}}",
                "{\"type\":\"text\",\"text\":\"正文\",\"marks\":[{\"type\":\"bold\",\"attrs\":{\"type\":\"1\"}}]}",
                "{\"type\":\"orderedList\",\"attrs\":{\"onclick\":null}}")) {
            var content = new RichDocContentDto(1, new ObjectMapper().readTree(
                    "{\"type\":\"doc\",\"content\":[" + node + "]}"), "正文");
            assertThatThrownBy(() -> service.updateDoc(existing.getId(),
                    new PatchDocRequest(null, null, content, null, null, 1)))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("不支持的节点属性");
        }
        verify(docMapper, never()).updateIfRevision(any(), anyInt());
    }

    private RichDocContentDto orderedListContent(String style) throws Exception {
        return new RichDocContentDto(1, new ObjectMapper().readTree("""
                {"type":"doc","content":[
                  {"type":"paragraph","attrs":{"uid":"heading","textAlign":null},
                   "content":[{"type":"text","text":"今日完成："}]},
                  {"type":"orderedList","attrs":{"start":1,"type":%s},"content":[
                    {"type":"listItem","content":[{"type":"paragraph","attrs":{"textAlign":null},
                     "content":[{"type":"text","text":"完成方案"}]}]},
                    {"type":"listItem","content":[{"type":"paragraph","attrs":{"textAlign":null}}]}
                  ]}
                ]}
                """.formatted(style)), "今日完成：\n\n1. 完成方案\n\n2. ");
    }

    @Test
    void richDocumentRejectsExecutableUrlsAndStaleRevision() throws Exception {
        DocEntity existing = doc("d-rich", "k1", "f1");
        when(docMapper.selectById(existing.getId())).thenReturn(existing);
        when(docMapper.selectList(any())).thenReturn(List.of(existing));
        RichDocContentDto unsafe = new RichDocContentDto(1, new ObjectMapper().readTree("""
                {"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"危险",
                "marks":[{"type":"link","attrs":{"href":"javascript:alert(1)"}}]}]}]}
                """), "[危险](javascript:alert(1))");

        assertThatThrownBy(() -> service.updateDoc(existing.getId(),
                new PatchDocRequest(null, List.of(), unsafe, null, null, 1)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("不安全");
        RichDocContentDto rawHtml = new RichDocContentDto(1,
                new ObjectMapper().readTree("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\"}]}"),
                "<script>alert(1)</script>");
        assertThatThrownBy(() -> service.updateDoc(existing.getId(),
                new PatchDocRequest(null, List.of(), rawHtml, null, null, 1)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("原始 HTML");
        assertThatThrownBy(() -> service.updateDoc(existing.getId(),
                new PatchDocRequest(null, null, null, null, null, 0)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("其他窗口");
        verify(docMapper, never()).updateIfRevision(any(), anyInt());
    }

    @Test
    void retiredProjectBriefDoesNotCreateKnowledgeContent() {
        assertThatThrownBy(() -> service.ensureProjectBrief(mock(ProjectDto.class), "wg"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("具体任务");
        verify(kbMapper, never()).insert(any(KbEntity.class));
        verify(docMapper, never()).insert(any(DocEntity.class));
    }

    @Test
    void ideaArchiveUsesIndependentAssetLibraryAndKeepsStableDocumentIdentity() {
        IdeaDto idea = mock(IdeaDto.class);
        when(idea.id()).thenReturn("idea1");
        when(idea.title()).thenReturn("测试点子");
        when(idea.detail()).thenReturn(mock(IdeaDto.Detail.class));
        when(docMapper.selectList(any())).thenReturn(List.of());
        AtomicReference<DocEntity> inserted = new AtomicReference<>();
        when(docMapper.selectById(anyString())).thenAnswer(invocation -> inserted.get());
        when(docMapper.insert(any(DocEntity.class))).thenAnswer(invocation -> {
            inserted.set(invocation.getArgument(0)); return 1;
        });
        DocDto first = service.ensureIdeaArchive(mock(ProjectDto.class), idea, "wg");
        DocDto second = service.ensureIdeaArchive(mock(ProjectDto.class), idea, "wg");
        assertThat(first.kbId()).isEqualTo(KnowledgeService.IDEA_ASSET_KB_ID);
        assertThat(first.links()).isEmpty();
        assertThat(second.id()).isEqualTo(first.id());
        verify(docMapper, times(1)).insert(any(DocEntity.class));
        verify(kbMapper, times(1)).insert(any(KbEntity.class));
    }

    @Test
    void sharedMemoryBecomesAVisibleEditableKnowledgeDocument() {
        when(kbMapper.selectById(KnowledgeService.MEMORY_KB_ID))
                .thenReturn(kb(KnowledgeService.MEMORY_KB_ID));
        when(folderMapper.selectById(KnowledgeService.MEMORY_FOLDER_ID))
                .thenReturn(folder(KnowledgeService.MEMORY_FOLDER_ID, KnowledgeService.MEMORY_KB_ID));
        when(docMapper.selectCount(any())).thenReturn(0L);
        when(docMapper.selectList(any())).thenReturn(List.of());

        DocDto dto = service.createSharedMemory("已确认的工作职责", "负责 WAO 产品与交付。", "responsibility", "wg");

        assertThat(dto.id()).hasSize(32).startsWith("m");
        assertThat(dto.kbId()).isEqualTo(KnowledgeService.MEMORY_KB_ID);
        assertThat(dto.folderId()).isEqualTo(KnowledgeService.MEMORY_FOLDER_ID);
        assertThat(dto.author()).isEqualTo("wg");
        assertThat(dto.blocks()).extracting(DocBlockDto::call, DocBlockDto::p)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("由 Codex 通过 WAO 记忆 MCP 保存 · 分类：responsibility", null),
                        org.assertj.core.groups.Tuple.tuple(null, "负责 WAO 产品与交付。"));
    }

    @Test
    void createDocInLegacyProjectKbDoesNotInheritProject() {
        KbEntity projectKb = kb("k1");
        projectKb.setProjectId("p1");
        when(kbMapper.selectById("k1")).thenReturn(projectKb);
        when(folderMapper.selectById("f1")).thenReturn(folder("f1", "k1"));
        when(docMapper.selectCount(any())).thenReturn(0L);
        when(docMapper.selectList(any())).thenReturn(List.of());

        DocDto dto = service.createDoc(new CreateDocRequest("k1", "f1", "项目文档"), "m-wadi");

        assertThat(dto.links()).isEmpty();
    }

    @Test
    void createDocSignsTheCurrentMemberNotAHardCodedOne() {
        when(kbMapper.selectById("k1")).thenReturn(kb("k1"));
        when(folderMapper.selectById("f1")).thenReturn(folder("f1", "k1"));
        when(docMapper.selectCount(any())).thenReturn(0L);
        when(docMapper.selectList(any())).thenReturn(List.of());

        DocDto dto = service.createDoc(new CreateDocRequest("k1", "f1", "新文档"), "m-someone-else");

        assertThat(dto.author()).isEqualTo("m-someone-else");
    }

    @Test
    void createDocStampsRealTimestampsInsteadOfLabels() {
        when(kbMapper.selectById("k1")).thenReturn(kb("k1"));
        when(folderMapper.selectById("f1")).thenReturn(folder("f1", "k1"));
        when(docMapper.selectCount(any())).thenReturn(0L);
        when(docMapper.selectList(any())).thenReturn(List.of());

        DocDto dto = service.createDoc(new CreateDocRequest("k1", "f1", "新文档"), "m1");

        assertThat(dto.created()).matches("\\d{4}-\\d{2}-\\d{2}T.*");
        assertThat(dto.updated()).matches("\\d{4}-\\d{2}-\\d{2}T.*");
    }

    @Test
    void createTemplateCopiesCurrentBlocksAndCreatesDocFromIt() {
        DocEntity source = doc("d-source", "k1", "f1");
        source.setBlocksJson("[{\"h\":\"每日计划\",\"hLevel\":1},{\"p\":\"今日重点\"}]");
        when(docMapper.selectById("d-source")).thenReturn(source);
        when(templateMapper.selectCount(any())).thenReturn(0L);

        var template = service.createTemplate(new CreateDocTemplateRequest(
                "每日计划", "每天复用", "d-source", null), "m1");

        var captor = org.mockito.ArgumentCaptor.forClass(DocTemplateEntity.class);
        verify(templateMapper).insert(captor.capture());
        DocTemplateEntity savedTemplate = captor.getValue();
        assertThat(template.blocks()).hasSize(2);
        assertThat(template.blocks().get(0).hLevel()).isEqualTo(1);

        when(templateMapper.selectById(savedTemplate.getId())).thenReturn(savedTemplate);
        when(kbMapper.selectById("k1")).thenReturn(kb("k1"));
        when(folderMapper.selectById("f1")).thenReturn(folder("f1", "k1"));
        when(docMapper.selectCount(any())).thenReturn(0L);
        when(docMapper.selectList(any())).thenReturn(List.of());

        DocDto created = service.createDoc(new CreateDocRequest("k1", "f1", "", savedTemplate.getId()), "m1");
        assertThat(created.blocks()).extracting(DocBlockDto::h, DocBlockDto::p)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("每日计划", null),
                        org.assertj.core.groups.Tuple.tuple(null, "今日重点"));
    }

    @Test
    void createTemplateAppliesTheSameMetadataAndRequestBodyLimitsAsUpdate() {
        DocEntity source = doc("d-source", "k1", "f1");
        source.setBlocksJson("[]");
        when(docMapper.selectById("d-source")).thenReturn(source);
        DocBlockDto emptyBlock = new DocBlockDto(null, null, "", null, null);

        assertThatThrownBy(() -> service.createTemplate(new CreateDocTemplateRequest(
                "名".repeat(129), "", "d-source", null), "m1"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("128");
        assertThatThrownBy(() -> service.createTemplate(new CreateDocTemplateRequest(
                "模板", "说明".repeat(257), "d-source", null), "m1"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("512");
        assertThatThrownBy(() -> service.createTemplate(new CreateDocTemplateRequest(
                "模板", "", "d-source", java.util.Collections.nCopies(5_001, emptyBlock)), "m1"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("5000 个块");
        assertThatThrownBy(() -> service.createTemplate(new CreateDocTemplateRequest(
                "模板", "", "d-source", List.of(
                        new DocBlockDto(null, null, "正".repeat(1_000_001), null, null))), "m1"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("1000000 个字符");
        verify(templateMapper, never()).insert(any(DocTemplateEntity.class));
    }

    @Test
    void createTemplateRejectsAnOversizedSourceDocumentSnapshot() {
        DocEntity source = doc("d-source", "k1", "f1");
        source.setBlocksJson("[\"" + "正".repeat(1_000_001) + "\"]");
        when(docMapper.selectById("d-source")).thenReturn(source);

        assertThatThrownBy(() -> service.createTemplate(new CreateDocTemplateRequest(
                "模板", "", "d-source", null), "m1"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("1000000 个字符");
        verify(templateMapper, never()).insert(any(DocTemplateEntity.class));
    }

    @Test
    void templateCannotBeReadAcrossMembers() {
        DocTemplateEntity template = new DocTemplateEntity();
        template.setId("dt1");
        template.setCreatedBy("m1");
        when(templateMapper.selectById("dt1")).thenReturn(template);
        when(kbMapper.selectById("k1")).thenReturn(kb("k1"));
        when(folderMapper.selectById("f1")).thenReturn(folder("f1", "k1"));

        assertThatThrownBy(() -> service.createDoc(new CreateDocRequest("k1", "f1", "", "dt1"), "m2"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateTemplatePersistsEditableContentWithMetadata() {
        DocTemplateEntity template = new DocTemplateEntity();
        template.setId("dt1");
        template.setName("旧模板");
        template.setDescription("旧说明");
        template.setBlocksJson("[{\"p\":\"旧正文\"}]");
        template.setCreatedBy("m1");
        template.setCreatedAt(java.time.LocalDateTime.now().minusDays(1));
        template.setUpdatedAt(java.time.LocalDateTime.now().minusDays(1));
        when(templateMapper.selectById("dt1")).thenReturn(template);

        List<DocBlockDto> blocks = List.of(
                new DocBlockDto(null, "模板标题", null, null, null, 1),
                new DocBlockDto(null, null, "修改后的正文", null, null));
        when(templateMapper.updateIfUnchanged(eq(template), any(LocalDateTime.class))).thenReturn(1);
        DocTemplateDto updated = service.updateTemplate("dt1",
                new PatchDocTemplateRequest("新模板", "新说明", blocks,
                        template.getUpdatedAt().atZone(java.time.ZoneId.of("Asia/Shanghai"))
                                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)), "m1");

        assertThat(updated.name()).isEqualTo("新模板");
        assertThat(updated.description()).isEqualTo("新说明");
        assertThat(updated.blocks()).extracting(DocBlockDto::h, DocBlockDto::p)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("模板标题", null),
                        org.assertj.core.groups.Tuple.tuple(null, "修改后的正文"));
        verify(templateMapper).updateIfUnchanged(eq(template), any(LocalDateTime.class));
    }

    @Test
    void updateTemplateRejectsMetadataBeyondDatabaseLimits() {
        DocTemplateEntity template = editableTemplate();
        when(templateMapper.selectById("dt1")).thenReturn(template);

        assertThatThrownBy(() -> service.updateTemplate("dt1",
                new PatchDocTemplateRequest("名".repeat(129), null, null,
                        template.getUpdatedAt().toString()), "m1"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("128");
        assertThatThrownBy(() -> service.updateTemplate("dt1",
                new PatchDocTemplateRequest(null, "说明".repeat(257), null,
                        template.getUpdatedAt().toString()), "m1"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("512");
        verify(templateMapper, never()).updateIfUnchanged(any(DocTemplateEntity.class), any(LocalDateTime.class));
    }

    @Test
    void updateTemplateRejectsUnboundedBlockCountAndSerializedContent() {
        DocTemplateEntity template = editableTemplate();
        when(templateMapper.selectById("dt1")).thenReturn(template);
        DocBlockDto emptyBlock = new DocBlockDto(null, null, "", null, null);

        assertThatThrownBy(() -> service.updateTemplate("dt1",
                new PatchDocTemplateRequest(null, null, java.util.Collections.nCopies(5_001, emptyBlock),
                        template.getUpdatedAt().toString()), "m1"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("5000 个块");
        assertThatThrownBy(() -> service.updateTemplate("dt1",
                new PatchDocTemplateRequest(null, null, List.of(
                        new DocBlockDto(null, null, "正".repeat(1_000_001), null, null)),
                        template.getUpdatedAt().toString()), "m1"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("1000000 个字符");
        verify(templateMapper, never()).updateIfUnchanged(any(DocTemplateEntity.class), any(LocalDateTime.class));
    }

    @Test
    void updateTemplateKeepsNullFieldsUnchanged() {
        DocTemplateEntity template = editableTemplate();
        when(templateMapper.selectById("dt1")).thenReturn(template);

        when(templateMapper.updateIfUnchanged(eq(template), any(LocalDateTime.class))).thenReturn(1);
        DocTemplateDto updated = service.updateTemplate("dt1", new PatchDocTemplateRequest(
                null, null, null, template.getUpdatedAt().toString()), "m1");

        assertThat(updated.name()).isEqualTo("旧模板");
        assertThat(updated.description()).isEqualTo("旧说明");
        assertThat(updated.blocks()).extracting(DocBlockDto::p).containsExactly("旧正文");
        verify(templateMapper).updateIfUnchanged(eq(template), any(LocalDateTime.class));
    }

    @Test
    void updateTemplateRejectsAStaleVersionAndPreservesTheStoredTemplate() {
        DocTemplateEntity template = editableTemplate();
        when(templateMapper.selectById("dt1")).thenReturn(template);

        assertThatThrownBy(() -> service.updateTemplate("dt1", new PatchDocTemplateRequest(
                "旧窗口的名称", null, null, template.getUpdatedAt().minusSeconds(1).toString()), "m1"))
                .isInstanceOf(cn.wayuwa.wao.common.ConflictException.class)
                .hasMessageContaining("其他窗口更新");

        assertThat(template.getName()).isEqualTo("旧模板");
        verify(templateMapper, never()).updateIfUnchanged(any(DocTemplateEntity.class), any(LocalDateTime.class));
    }

    @Test
    void updateTemplateReturnsConflictWhenAnotherWriteWinsAfterTheRead() {
        DocTemplateEntity template = editableTemplate();
        LocalDateTime expected = template.getUpdatedAt();
        when(templateMapper.selectById("dt1")).thenReturn(template);
        when(templateMapper.updateIfUnchanged(eq(template), eq(expected))).thenReturn(0);

        assertThatThrownBy(() -> service.updateTemplate("dt1", new PatchDocTemplateRequest(
                "竞争保存", null, null, expected.toString()), "m1"))
                .isInstanceOf(cn.wayuwa.wao.common.ConflictException.class)
                .hasMessageContaining("其他窗口更新");
    }

    private DocTemplateEntity editableTemplate() {
        DocTemplateEntity template = new DocTemplateEntity();
        template.setId("dt1");
        template.setName("旧模板");
        template.setDescription("旧说明");
        template.setBlocksJson("[{\"p\":\"旧正文\"}]");
        template.setCreatedBy("m1");
        template.setCreatedAt(java.time.LocalDateTime.now().minusDays(1));
        template.setUpdatedAt(java.time.LocalDateTime.now().minusDays(1));
        return template;
    }

    @Test
    void reorderDocsPersistsOrderAndCrossFolderMove() {
        when(kbMapper.selectById("k1")).thenReturn(kb("k1"));
        KbFolderEntity f1 = folder("f1", "k1");
        KbFolderEntity f2 = folder("f2", "k1");
        DocEntity d1 = doc("d1", "k1", "f1");
        DocEntity d2 = doc("d2", "k1", "f1");
        when(folderMapper.selectList(any())).thenReturn(List.of(f1, f2));
        when(docMapper.selectList(any())).thenReturn(List.of(d1, d2));
        when(docMapper.updateTreeOrders(eq("k1"), any())).thenReturn(2);

        service.reorderDocs("k1", new ReorderDocsRequest(List.of(
                new ReorderDocsRequest.FolderOrder("f1", List.of("d2")),
                new ReorderDocsRequest.FolderOrder("f2", List.of("d1")))));

        assertThat(d2.getFolderId()).isEqualTo("f1");
        assertThat(d2.getOrd()).isEqualTo(1);
        assertThat(d1.getFolderId()).isEqualTo("f2");
        assertThat(d1.getOrd()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DocEntity>> ordered = ArgumentCaptor.forClass(List.class);
        verify(docMapper).updateTreeOrders(eq("k1"), ordered.capture());
        assertThat(ordered.getValue()).extracting(DocEntity::getId).containsExactly("d2", "d1");
    }

    @Test
    void reorderDocsReturnsConflictWhenBatchCannotMatchEveryValidatedDocument() {
        when(kbMapper.selectById("k1")).thenReturn(kb("k1"));
        when(folderMapper.selectList(any())).thenReturn(List.of(folder("f1", "k1")));
        when(docMapper.selectList(any())).thenReturn(List.of(doc("d1", "k1", "f1"), doc("d2", "k1", "f1")));
        when(docMapper.updateTreeOrders(eq("k1"), any())).thenReturn(1);

        assertThatThrownBy(() -> service.reorderDocs("k1", new ReorderDocsRequest(List.of(
                new ReorderDocsRequest.FolderOrder("f1", List.of("d2", "d1"))))))
                .isInstanceOf(cn.wayuwa.wao.common.ConflictException.class)
                .hasMessageContaining("其他页面更新");
    }

    @Test
    void reorderDocsRejectsMissingDocuments() {
        when(kbMapper.selectById("k1")).thenReturn(kb("k1"));
        when(folderMapper.selectList(any())).thenReturn(List.of(folder("f1", "k1")));
        when(docMapper.selectList(any())).thenReturn(List.of(doc("d1", "k1", "f1")));

        assertThatThrownBy(() -> service.reorderDocs("k1", new ReorderDocsRequest(List.of(
                new ReorderDocsRequest.FolderOrder("f1", List.of())))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("全部");
    }

    @Test
    void createDocThrowsWhenKbMissing() {
        when(kbMapper.selectById("missing")).thenReturn(null);

        assertThatThrownBy(() -> service.createDoc(new CreateDocRequest("missing", "f1", "标题"), "m1"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void createDocThrowsWhenKbIsInTrash() {
        KbEntity trashed = kb("k1");
        trashed.setDeletedAt(LocalDateTime.now());
        when(kbMapper.selectById("k1")).thenReturn(trashed);

        assertThatThrownBy(() -> service.createDoc(new CreateDocRequest("k1", "f1", "标题"), "m1"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void createDocThrowsWhenFolderBelongsToAnotherKb() {
        when(kbMapper.selectById("k1")).thenReturn(kb("k1"));
        when(folderMapper.selectById("f1")).thenReturn(folder("f1", "other-kb"));

        assertThatThrownBy(() -> service.createDoc(new CreateDocRequest("k1", "f1", "标题"), "m1"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void revisionReadUsesOnlyTheVersionLookupAndKeepsMissingDocumentsUnavailable() {
        when(docMapper.selectAliveRevision("d1")).thenReturn(7);
        when(docMapper.selectAliveRevision("deleted")).thenReturn(null);
        assertThat(service.getDocRevision("d1")).isEqualTo(7);
        assertThatThrownBy(() -> service.getDocRevision("deleted")).isInstanceOf(NotFoundException.class);
        verify(docMapper, never()).selectById(anyString());
        verify(docMapper, never()).selectList(any());
        verify(docMapper, never()).selectBacklinkSources(anyString());
    }

    @Test
    void idempotentDailyDocumentKeepsDeletedTargetsWithoutBacklinks() {
        DocEntity deleted = doc("d1", "k1", "f1");
        deleted.setAuthorId("m1");
        deleted.setDeletedAt(LocalDateTime.now());
        when(docMapper.selectById("d1")).thenReturn(deleted);
        var result = service.createDailyRhythmDoc("d1", "k1", "f1", null, "日常", List.of(), "m1");
        assertThat(result.backlinks()).isEmpty();
        verify(docMapper, never()).selectBacklinkSources(anyString());
    }

    @Test
    void getDocComputesBacklinksFromOtherDocsLinks() {
        DocEntity target = doc("d1", "k1", "f1");
        DocEntity source = doc("d2", "k1", "f1");
        source.setBlocksJson("[{\"p\":\"引用了目标文档\"}]");
        source.setLinksJson("[{\"type\":\"doc\",\"id\":\"d1\"}]");
        when(docMapper.selectById("d1")).thenReturn(target);
        when(docMapper.selectBacklinkSources("d1")).thenReturn(List.of(source));

        DocDto dto = service.getDoc("d1");

        assertThat(dto.backlinks()).hasSize(1);
        assertThat(dto.backlinks().get(0).id()).isEqualTo("d2");
        assertThat(dto.backlinks().get(0).snippet()).contains("引用了目标文档");
        verify(docMapper, never()).selectList(any());
    }

    @Test
    void listDocsBuildsAllBacklinksFromOneDocumentQuery() {
        DocEntity first = doc("d1", "k1", "f1");
        DocEntity second = doc("d2", "k1", "f1");
        DocEntity source = doc("d3", "k1", "f1");
        source.setBlocksJson("[{\"p\":\"同时引用两篇文档\"}]");
        source.setLinksJson("[{\"type\":\"doc\",\"id\":\"d1\"},{\"type\":\"doc\",\"id\":\"d2\"}]");
        when(docMapper.selectList(any())).thenReturn(List.of(first, second, source));

        List<DocDto> docs = service.listDocs();

        assertThat(docs).extracting(DocDto::id).containsExactly("d1", "d2", "d3");
        assertThat(docs.get(0).backlinks()).extracting(cn.wayuwa.wao.dto.BacklinkDto::id)
                .containsExactly("d3");
        assertThat(docs.get(1).backlinks()).extracting(cn.wayuwa.wao.dto.BacklinkDto::id)
                .containsExactly("d3");
        assertThat(docs.get(2).backlinks()).isEmpty();
        verify(docMapper, times(1)).selectList(any());
    }

    @Test
    void listDocsParsesEachDocumentsBlocksAndLinksOnlyOnce() {
        DocEntity first = doc("d1", "k1", "f1");
        DocEntity source = doc("d2", "k1", "f1");
        source.setBlocksJson("[{\"p\":\"引用了目标文档\"}]");
        source.setLinksJson("[{\"type\":\"doc\",\"id\":\"d1\"}]");
        when(docMapper.selectList(any())).thenReturn(List.of(first, source));
        CountingObjectMapper json = new CountingObjectMapper();
        KnowledgeService measured = new KnowledgeService(groupMapper, kbMapper, folderMapper, docMapper,
                templateMapper, memberMapper, json, taskNotifier, archiveVersions);

        List<DocDto> docs = measured.listDocs();

        assertThat(json.readCount).isEqualTo(4);
        assertThat(docs.get(0).backlinks()).extracting(cn.wayuwa.wao.dto.BacklinkDto::id)
                .containsExactly("d2");
        assertThat(docs.get(1).blocks()).containsExactly(new DocBlockDto(
                null, null, "引用了目标文档", null, null, null));
    }

    private static final class CountingObjectMapper extends ObjectMapper {
        private int readCount;

        @Override
        public <T> T readValue(String content, TypeReference<T> valueTypeRef) throws JsonProcessingException {
            readCount++;
            return super.readValue(content, valueTypeRef);
        }
    }

    @Test
    void getDocThrowsWhenAlreadyInTrash() {
        DocEntity trashed = doc("d1", "k1", "f1");
        trashed.setDeletedAt(LocalDateTime.now());
        when(docMapper.selectById("d1")).thenReturn(trashed);

        assertThatThrownBy(() -> service.getDoc("d1")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateDocBumpsRevisionOnlyWhenContentChanges() {
        DocEntity d = doc("d1", "k1", "f1");
        when(docMapper.selectById("d1")).thenReturn(d);
        when(docMapper.selectList(any())).thenReturn(List.of(d));

        DocDto untouched = service.updateDoc("d1", new PatchDocRequest(null, null, List.of(), null));
        assertThat(untouched.rev()).isEqualTo(1);

        DocDto edited = service.updateDoc("d1", new PatchDocRequest(null, List.of(new DocBlockDto(null, null, "正文", null, null)), null, null));
        assertThat(edited.rev()).isEqualTo(2);
        assertThat(edited.updated()).matches("\\d{4}-\\d{2}-\\d{2}T.*");
    }

    @Test
    void createKbSeedsDefaultFolder() {
        when(kbMapper.selectCount(null)).thenReturn(0L);

        KbDto dto = service.createKb(new CreateKbRequest("新知识库", "简介", null, null, null, null, null));

        assertThat(dto.id()).startsWith("kb");
        assertThat(dto.folders()).hasSize(1);
        assertThat(dto.folders().get(0).name()).isEqualTo("文档");
        assertThat(dto.notes()).isFalse();
    }

    @Test
    void legacyProjectKbCanBeRenamedIndependently() {
        KbEntity kb = kb("k1");
        kb.setName("项目知识库");
        kb.setProjectId("p1");
        kb.setAuto(false);
        kb.setIcon("ri-book-2-line");
        when(kbMapper.selectById("k1")).thenReturn(kb);
        when(folderMapper.selectList(any())).thenReturn(List.of());
        when(docMapper.selectList(any())).thenReturn(List.of());

        KbDto dto = service.updateKb("k1", new PatchKbRequest("改名", "改简介", "ri-star-line", null, "g-custom", null));

        assertThat(dto.name()).isEqualTo("改名");
        assertThat(dto.desc()).isEqualTo("改简介");
        assertThat(dto.project()).isNull();
        assertThat(dto.icon()).isEqualTo("ri-star-line");
        assertThat(kb.getGroupId()).isEqualTo("g-custom");
    }

    @Test
    void legacyProjectLinksAreHiddenButTaskAndDocumentLinksRemain() {
        DocEntity d = doc("d1", "k1", "f1");
        d.setLinksJson("[{\"type\":\"project\",\"id\":\"p1\"},{\"type\":\"task\",\"id\":\"t1\"},{\"type\":\"doc\",\"id\":\"d2\"}]");
        when(docMapper.selectById("d1")).thenReturn(d);
        when(docMapper.selectList(any())).thenReturn(List.of(d));
        assertThat(service.getDoc("d1").links()).containsExactly(
                new DocLinkDto("task", "t1"), new DocLinkDto("doc", "d2"));
        verify(docMapper, never()).updateById(any(DocEntity.class));
    }

    @Test
    void getKbThrowsWhenMissing() {
        when(kbMapper.selectById("nope")).thenReturn(null);
        assertThatThrownBy(() -> service.getKb("nope")).isInstanceOf(NotFoundException.class);
    }

    // ---------------- 回收站 ----------------

    @Test
    void deleteDocMovesItToTrashInsteadOfDroppingTheRow() {
        DocEntity d = doc("d1", "k1", "f1");
        when(docMapper.selectById("d1")).thenReturn(d);

        service.deleteDoc("d1", "m1");

        assertThat(d.getDeletedAt()).isNotNull();
        assertThat(d.getDeletedBy()).isEqualTo("m1");
        assertThat(d.getCascadeDel()).isFalse();
        verify(docMapper).markDeleted(eq("d1"), any(LocalDateTime.class), eq("m1"), eq(false));
        verify(docMapper, never()).deleteById(any(java.io.Serializable.class));
    }

    @Test
    void deleteKbCascadesToFoldersAndDocsWithoutDroppingRows() {
        when(kbMapper.selectById("k1")).thenReturn(kb("k1"));
        KbFolderEntity f = folder("f1", "k1");
        DocEntity d = doc("d1", "k1", "f1");
        when(folderMapper.selectList(any())).thenReturn(List.of(f));
        when(docMapper.selectList(any())).thenReturn(List.of(d));

        service.deleteKb("k1", "m1");

        assertThat(f.getDeletedAt()).isNotNull();
        assertThat(f.getCascadeDel()).isTrue();
        assertThat(d.getDeletedAt()).isNotNull();
        assertThat(d.getCascadeDel()).isTrue();
        verify(kbMapper, never()).deleteById(any(java.io.Serializable.class));
    }

    @Test
    void deleteKbRefusesTheNotesKb() {
        KbEntity notes = kb("kb-notes");
        notes.setNotes(true);
        when(kbMapper.selectById("kb-notes")).thenReturn(notes);

        assertThatThrownBy(() -> service.deleteKb("kb-notes", "m1")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void trashListHidesEntriesDeletedAlongWithTheirParent() {
        KbEntity trashedKb = kb("k1");
        trashedKb.setDeletedAt(LocalDateTime.now());
        KbFolderEntity cascaded = folder("f1", "k1");
        cascaded.setDeletedAt(LocalDateTime.now());
        cascaded.setCascadeDel(true);
        DocEntity ownDelete = doc("d1", "k2", "f2");
        ownDelete.setDeletedAt(LocalDateTime.now());
        ownDelete.setCascadeDel(false);

        when(kbMapper.selectList(any())).thenReturn(List.of(trashedKb));
        // cascade 条目由 ne(cascadeDel, true) 过滤掉，这里模拟已过滤的结果
        when(folderMapper.selectList(any())).thenReturn(List.of());
        when(docMapper.selectList(any())).thenReturn(List.of(ownDelete));

        List<TrashEntryDto> entries = service.listTrash();

        assertThat(entries).extracting(TrashEntryDto::type).containsExactlyInAnyOrder("kb", "doc");
        assertThat(entries).extracting(TrashEntryDto::id).doesNotContain("f1");
        assertThat(entries).allSatisfy(e -> assertThat(e.expiresAt()).isNotNull());
    }

    @Test
    void restoringADocAlsoRevivesItsTrashedKbSoItHasSomewhereToLive() {
        DocEntity d = doc("d1", "k1", "f1");
        d.setDeletedAt(LocalDateTime.now());
        KbFolderEntity f = folder("f1", "k1");
        f.setDeletedAt(LocalDateTime.now());
        f.setCascadeDel(true);
        KbEntity k = kb("k1");
        k.setDeletedAt(LocalDateTime.now());
        when(docMapper.selectById("d1")).thenReturn(d);
        when(folderMapper.selectById("f1")).thenReturn(f);
        when(kbMapper.selectById("k1")).thenReturn(k);

        service.restore("doc", "d1");

        assertThat(d.getDeletedAt()).isNull();
        assertThat(f.getDeletedAt()).isNull();
        assertThat(k.getDeletedAt()).isNull();
        verify(docMapper).clearDeleted("d1");
        verify(folderMapper).clearDeleted("f1");
        verify(kbMapper).clearDeleted("k1");
    }

    @Test
    void restoringAProjectKnowledgeBaseRevivesItsTreeWithoutChangingTheProjectLink() {
        KbEntity k = kb("k-project");
        k.setProjectId("p1");
        k.setDeletedAt(LocalDateTime.now());
        k.setDeletedBy("m1");
        KbFolderEntity f = folder("f-project", k.getId());
        f.setDeletedAt(k.getDeletedAt());
        f.setCascadeDel(true);
        DocEntity d = doc("d-project", k.getId(), f.getId());
        d.setDeletedAt(k.getDeletedAt());
        d.setDeletedBy("m1");
        d.setCascadeDel(true);
        when(kbMapper.selectById(k.getId())).thenReturn(k);
        when(folderMapper.selectList(any())).thenReturn(List.of(f));
        when(docMapper.selectList(any())).thenReturn(List.of(d));

        service.restore("kb", k.getId());

        assertThat(k.getProjectId()).isEqualTo("p1");
        assertThat(k.getDeletedAt()).isNull();
        assertThat(f.getDeletedAt()).isNull();
        assertThat(d.getDeletedAt()).isNull();
        verify(kbMapper).clearDeleted(k.getId());
        verify(folderMapper).clearDeleted(f.getId());
        verify(docMapper).clearDeleted(d.getId());
    }

    @Test
    void restoreThrowsWhenEntryIsNotInTrash() {
        when(docMapper.selectById("d1")).thenReturn(doc("d1", "k1", "f1"));
        assertThatThrownBy(() -> service.restore("doc", "d1")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.restore("banana", "x")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void purgeExpiredDropsOnlyEntriesPastTheRetentionWindow() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 25, 10, 0);
        DocEntity expired = doc("d-old", "k1", "f1");
        expired.setDeletedAt(now.minusDays(KnowledgeService.TRASH_DAYS).minusMinutes(1));
        when(kbMapper.selectList(any())).thenReturn(List.of());
        when(folderMapper.selectList(any())).thenReturn(List.of());
        when(docMapper.selectList(any())).thenReturn(List.of(expired));

        int removed = service.purgeExpired(now);

        assertThat(removed).isEqualTo(1);
        verify(docMapper).deleteById("d-old");
    }

    // ---------------- 小记 ----------------

    @Test
    void createNoteLandsInTheNotesKbAndTakesItsTitleFromTheFirstLine() {
        KbEntity notes = kb("kb-notes");
        notes.setNotes(true);
        KbFolderEntity folder = folder("kb-notes-f", "kb-notes");
        when(kbMapper.selectList(any())).thenReturn(List.of(notes));
        when(folderMapper.selectList(any())).thenReturn(List.of(folder));
        when(docMapper.selectCount(any())).thenReturn(3L);
        when(docMapper.selectList(any())).thenReturn(List.of());

        DocDto dto = service.createNote(
                new CreateNoteRequest(null, List.of(new DocBlockDto(null, null, "记一下：周四要交社保材料", null, null))),
                "m1");

        assertThat(dto.kbId()).isEqualTo("kb-notes");
        assertThat(dto.folderId()).isEqualTo("kb-notes-f");
        assertThat(dto.title()).isEqualTo("记一下：周四要交社保材料");
        assertThat(dto.author()).isEqualTo("m1");
    }

    @Test
    void createNoteReusesTheFolderNamedAfterTheAuthor() {
        KbEntity notes = kb("kb-notes");
        notes.setNotes(true);
        member("wd", "娃弟");
        KbFolderEntity mine = folder("kb-notes-wd", "kb-notes");
        mine.setName("娃弟");
        when(kbMapper.selectList(any())).thenReturn(List.of(notes));
        when(folderMapper.selectList(any())).thenReturn(List.of(mine));
        when(docMapper.selectCount(any())).thenReturn(0L);
        when(docMapper.selectList(any())).thenReturn(List.of());

        DocDto dto = service.createNote(new CreateNoteRequest("随手一记", List.of()), "wd");

        assertThat(dto.folderId()).isEqualTo("kb-notes-wd");
        verify(folderMapper, never()).insert(any(KbFolderEntity.class));
    }

    @Test
    void createNoteCreatesTheAuthorFolderOnFirstNote() {
        KbEntity notes = kb("kb-notes");
        notes.setNotes(true);
        member("wg", "娃哥");
        when(kbMapper.selectList(any())).thenReturn(List.of(notes));
        when(folderMapper.selectList(any())).thenReturn(List.of());
        when(folderMapper.selectById("kb-notes-wg")).thenReturn(null);
        when(folderMapper.selectCount(any())).thenReturn(1L);
        when(docMapper.selectCount(any())).thenReturn(0L);
        when(docMapper.selectList(any())).thenReturn(List.of());

        DocDto dto = service.createNote(new CreateNoteRequest("随手一记", List.of()), "wg");

        var captor = org.mockito.ArgumentCaptor.forClass(KbFolderEntity.class);
        verify(folderMapper).insert(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("娃哥");
        assertThat(dto.folderId()).isEqualTo("kb-notes-wg");
    }

    @Test
    void createNoteFallsBackToTheSharedFolderWhenTheAuthorIsUnknown() {
        KbEntity notes = kb("kb-notes");
        notes.setNotes(true);
        KbFolderEntity shared = folder("kb-notes-f", "kb-notes");
        when(kbMapper.selectList(any())).thenReturn(List.of(notes));
        when(folderMapper.selectList(any())).thenReturn(List.of(shared));
        when(docMapper.selectCount(any())).thenReturn(0L);
        when(docMapper.selectList(any())).thenReturn(List.of());

        DocDto dto = service.createNote(new CreateNoteRequest("随手一记", List.of()), null);

        assertThat(dto.folderId()).isEqualTo("kb-notes-f");
    }

    @Test
    void createNoteRebuildsTheNotesKbWhenItIsMissing() {
        when(kbMapper.selectList(any())).thenReturn(List.of());
        when(kbMapper.selectById("kb-notes")).thenReturn(null);
        when(folderMapper.selectList(any())).thenReturn(List.of());
        when(folderMapper.selectById("kb-notes-f")).thenReturn(null);
        when(docMapper.selectCount(any())).thenReturn(0L);
        when(docMapper.selectList(any())).thenReturn(List.of());

        DocDto dto = service.createNote(new CreateNoteRequest(null, List.of()), "m1");

        verify(kbMapper, times(1)).insert(any(KbEntity.class));
        verify(folderMapper, times(1)).insert(any(KbFolderEntity.class));
        assertThat(dto.title()).isEqualTo("未命名小记");
    }

    @Test
    void createNoteWithStableIdDoesNotInsertOrNotifyTwiceOnRetry() throws Exception {
        String id = "0123456789abcdef0123456789abcdef";
        DocEntity existing = doc(id, KnowledgeService.NOTES_KB_ID, "kb-notes-wg");
        existing.setAuthorId("wg");
        existing.setTitle("网络抖动前已经存好");
        List<DocBlockDto> blocks = List.of(new DocBlockDto(null, null, "网络抖动前已经存好", null, null));
        existing.setBlocksJson(new ObjectMapper().writeValueAsString(blocks));
        when(docMapper.selectById(id)).thenReturn(existing);
        when(docMapper.selectList(any())).thenReturn(List.of(existing));

        DocDto dto = service.createNote(id, new CreateNoteRequest("网络抖动前已经存好", blocks), "wg");

        assertThat(dto.id()).isEqualTo(id);
        verify(docMapper, never()).insert(any(DocEntity.class));
        verify(docMapper, never()).updateById(any(DocEntity.class));
        verify(taskNotifier, never()).notifyNoteCreated(any(), any(), any(), any());
    }

    @Test
    void createNoteRetryUpdatesTheSameDocumentWhenDraftChanged() {
        String id = "fedcba9876543210fedcba9876543210";
        DocEntity existing = doc(id, KnowledgeService.NOTES_KB_ID, "kb-notes-wg");
        existing.setAuthorId("wg");
        existing.setTitle("旧草稿");
        existing.setBlocksJson("[{\"p\":\"旧草稿\"}]");
        when(docMapper.selectById(id)).thenReturn(existing);
        when(docMapper.selectList(any())).thenReturn(List.of(existing));

        DocDto dto = service.createNote(id, new CreateNoteRequest("重试前补充过",
                List.of(new DocBlockDto(null, null, "重试前补充过", null, null))), "wg");

        assertThat(dto.id()).isEqualTo(id);
        assertThat(dto.title()).isEqualTo("重试前补充过");
        assertThat(dto.rev()).isEqualTo(2);
        verify(docMapper).updateContentSnapshot(existing);
        verify(docMapper, never()).insert(any(DocEntity.class));
        verify(taskNotifier, never()).notifyNoteCreated(any(), any(), any(), any());
    }

    @Test
    void createNoteWithStableIdRejectsCollisionsAndInvalidIds() {
        String occupiedId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        DocEntity occupied = doc(occupiedId, "another-kb", "another-folder");
        occupied.setAuthorId("someone-else");
        when(docMapper.selectById(occupiedId)).thenReturn(occupied);

        assertThatThrownBy(() -> service.createNote(occupiedId,
                new CreateNoteRequest("不能覆盖", List.of()), "wg"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("已被占用");
        assertThatThrownBy(() -> service.createNote("../bad-id",
                new CreateNoteRequest("不能创建", List.of()), "wg"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("不合法");
        verify(docMapper, never()).insert(any(DocEntity.class));
    }
    @Test
    void noteTagsParticipateInRevisionAndLegacyUpdatesPreserveThem() {
        DocEntity note = doc("tagged", "kb-notes", "f");
        note.setRev(3);
        note.setTagsJson("[\"工作\"]");
        when(docMapper.selectById("tagged")).thenReturn(note);
        when(docMapper.updateIfRevision(any(), eq(3))).thenReturn(1);
        DocDto updated = service.updateDoc("tagged", new PatchDocRequest(null, null, null, null, null, 3,
                List.of(" 灵感 ", "灵感")));
        assertThat(updated.tags()).containsExactly("灵感");
        assertThat(updated.rev()).isEqualTo(4);
        verify(docMapper).updateIfRevision(any(), eq(3));
        service.updateDoc("tagged", new PatchDocRequest("旧端修改正文", null, null, null));
        assertThat(note.getTagsJson()).isEqualTo("[\"灵感\"]");
        assertThatThrownBy(() -> service.updateDoc("tagged",
                new PatchDocRequest(null, null, null, null, null, 3, List.of())))
                .isInstanceOf(ConflictException.class);
        service.updateDoc("tagged", new PatchDocRequest(null, null, null, null, null, null, List.of()));
        assertThat(note.getTagsJson()).isEqualTo("[]");
    }

    @Test
    void idempotentNoteRetryPreservesOmittedTagsAndAcceptsExplicitTags() {
        DocEntity note = doc("tag-retry", "kb-notes", "f");
        note.setTagsJson("[\"工作\"]");
        note.setAuthorId("wg");
        when(docMapper.selectById("tag-retry")).thenReturn(note);
        DocDto legacy = service.createNote("tag-retry", new CreateNoteRequest("重试", List.of()), "wg");
        assertThat(legacy.tags()).containsExactly("工作");
        DocDto tagged = service.createNote("tag-retry", new CreateNoteRequest("重试", List.of(), null, List.of("灵感")), "wg");
        assertThat(tagged.tags()).containsExactly("灵感");
        verify(docMapper, never()).insert(any(DocEntity.class));
    }

}
