package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.DrawingFileEntity;
import cn.wayuwa.wao.domain.DrawingFileVersionEntity;
import cn.wayuwa.wao.dto.CreateDrawingFileRequest;
import cn.wayuwa.wao.dto.RenameDrawingFileRequest;
import cn.wayuwa.wao.dto.SaveDrawingFileRequest;
import cn.wayuwa.wao.mapper.DrawingFileMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DrawingFileServiceTest {
    @Test
    void retriesReadTheCommittedSceneAfterAnOverlappingSave() throws Exception {
        // MySQL's repeatable-read snapshot would hide the first request's commit
        // after saveScene waits for it and returns zero for the old revision.
        var attributes = new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource();
        for (String method : List.of("save", "restore")) {
            var signature = method.equals("save")
                    ? DrawingFileService.class.getMethod(method, String.class, SaveDrawingFileRequest.class, String.class)
                    : DrawingFileService.class.getMethod(method, String.class, int.class, int.class, String.class);
            assertThat(attributes.getTransactionAttribute(signature, DrawingFileService.class).getIsolationLevel())
                    .isEqualTo(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        }
    }

    @Test void linkedDrawingUsesLibraryPermissionEvenForOriginalCreator() {
        var access = mock(KnowledgeAccessService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "knowledgeAccess", access);
        var drawing = file("linked", OWNER, 1); drawing.setKbId("library"); drawing.setDocId("doc");
        when(mapper.findOwned("linked", OWNER)).thenReturn(drawing);
        when(mapper.findKnowledge("linked")).thenReturn(drawing);
        when(mapper.listOwned(OWNER)).thenReturn(List.of(drawing));
        assertThatThrownBy(() -> service.get("linked", OWNER)).isInstanceOf(NotFoundException.class);
        assertThat(service.list(OWNER)).isEmpty();
        when(access.visible("library", OTHER)).thenReturn(true);
        assertThat(service.get("linked", OTHER).id()).isEqualTo("linked");
        when(access.visible("library", OTHER)).thenReturn(false);
        assertThatThrownBy(() -> service.versions("linked", OTHER)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.restore("linked", 1, 1, OTHER)).isInstanceOf(NotFoundException.class);
        verify(mapper, never()).findVersion("linked", 1, OWNER);
    }

    private static final String OWNER = "wd";
    private static final String OTHER = "wg";
    private static final String SCENE = "{\"type\":\"excalidraw\",\"elements\":[],\"appState\":{},\"files\":{}}";
    private static final String CHANGED_SCENE = "{\"type\":\"excalidraw\",\"elements\":[{\"id\":\"shape\"}],\"appState\":{}}";
    private final DrawingFileMapper mapper = mock(DrawingFileMapper.class);
    private final DrawingFileService service = new DrawingFileService(mapper, new ObjectMapper());

    @Test
    void createsEmptyFileForCurrentMemberOnly() {
        service.create(new CreateDrawingFileRequest("  "), OWNER);

        ArgumentCaptor<DrawingFileEntity> captor = ArgumentCaptor.forClass(DrawingFileEntity.class);
        verify(mapper).insert(captor.capture());
        DrawingFileEntity created = captor.getValue();
        assertThat(created.getOwnerMemberId()).isEqualTo(OWNER);
        assertThat(created.getTitle()).isEqualTo(DrawingFileService.DEFAULT_TITLE);
        assertThat(created.getSceneJson()).isEqualTo(DrawingFileService.EMPTY_SCENE);
        assertThat(created.getRevision()).isEqualTo(1);
        verify(mapper, never()).insertVersion(any(), anyInt(), any(), any());
    }

    @Test
    void createRequestAcceptsLegacyAndGroupJson() throws Exception {
        ObjectMapper json = new ObjectMapper();
        assertThat(json.readValue("{\"title\":\"旧格式\"}", CreateDrawingFileRequest.class).groupId()).isNull();
        assertThat(json.readValue("{\"title\":\"新格式\",\"groupId\":\"g1\"}", CreateDrawingFileRequest.class).groupId()).isEqualTo("g1");
    }

    @Test
    void createInGroupChecksOwnerBeforeCreatingAndAssignsAtomically() {
        var groups = mock(cn.wayuwa.wao.mapper.DrawingGroupMapper.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "groups", groups);
        when(groups.owned("private", OWNER)).thenReturn(0);
        assertThatThrownBy(() -> service.create(new CreateDrawingFileRequest("草图", "private"), OWNER)).isInstanceOf(NotFoundException.class);
        verify(mapper, never()).insert(any(DrawingFileEntity.class));
        when(groups.owned("g1", OWNER)).thenReturn(1);
        var created = service.create(new CreateDrawingFileRequest("草图", "g1"), OWNER);
        verify(groups).assign(OWNER, created.id(), "g1");
    }

    @Test
    void listAndGetAlwaysCarryOwnerScope() {
        DrawingFileEntity file = file("dr1", OWNER, 3);
        when(mapper.listOwned(OWNER)).thenReturn(List.of(file));
        when(mapper.findOwned("dr1", OWNER)).thenReturn(file);

        assertThat(service.list(OWNER)).singleElement().satisfies(summary -> {
            assertThat(summary.id()).isEqualTo("dr1");
            assertThat(summary.revision()).isEqualTo(3);
        });
        assertThat(service.get("dr1", OWNER).sceneJson()).isEqualTo(SCENE);
        verify(mapper).listOwned(OWNER);
        verify(mapper).findOwned("dr1", OWNER);
    }

    @Test
    void anotherMembersFileLooksMissing() {
        when(mapper.findOwned("dr1", OTHER)).thenReturn(null);

        assertThatThrownBy(() -> service.get("dr1", OTHER))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("草图文件不存在");
        verify(mapper).findOwned("dr1", OTHER);
    }

    @Test
    void savesValidSceneWithOptimisticRevision() {
        DrawingFileEntity before = file("dr1", OWNER, 2);
        DrawingFileEntity after = file("dr1", OWNER, 3);
        when(mapper.findOwned("dr1", OWNER)).thenReturn(before, after);
        after.setSceneJson(CHANGED_SCENE);
        when(mapper.saveScene(eq("dr1"), eq(OWNER), eq(CHANGED_SCENE), eq(2), any(LocalDateTime.class)))
                .thenReturn(1);

        var saved = service.save("dr1", new SaveDrawingFileRequest(CHANGED_SCENE, 2), OWNER);

        assertThat(saved.revision()).isEqualTo(3);
        verify(mapper).saveScene(eq("dr1"), eq(OWNER), eq(CHANGED_SCENE), eq(2), any(LocalDateTime.class));
        verify(mapper).insertVersion(eq("dr1"), eq(3), eq(CHANGED_SCENE), any(LocalDateTime.class));
        verify(mapper).pruneVersions("dr1");
    }

    @Test
    void unchangedOfficialSerializationDoesNotCreateVersion() {
        String officialScene = "{\"type\":\"excalidraw\",\"version\":2,\"source\":\"https://excalidraw.com\","
                + "\"elements\":[],\"appState\":{\"viewBackgroundColor\":\"#ffffff\"}}";
        DrawingFileEntity current = file("dr1", OWNER, 2);
        when(mapper.findOwned("dr1", OWNER)).thenReturn(current);

        assertThat(service.save("dr1", new SaveDrawingFileRequest(officialScene, 2), OWNER).revision())
                .isEqualTo(2);
        verify(mapper, never()).saveScene(any(), any(), any(), anyInt(), any());
        verify(mapper, never()).insertVersion(any(), anyInt(), any(), any());
    }

    @Test
    void staleRevisionNeverOverwritesNewerScene() {
        DrawingFileEntity current = file("dr1", OWNER, 4);
        when(mapper.findOwned("dr1", OWNER)).thenReturn(current);
        when(mapper.saveScene(eq("dr1"), eq(OWNER), eq(CHANGED_SCENE), anyInt(), any(LocalDateTime.class)))
                .thenReturn(0);

        assertThatThrownBy(() -> service.save("dr1", new SaveDrawingFileRequest(CHANGED_SCENE, 2), OWNER))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("草稿已保留");
    }

    @Test
    void sameSceneAlreadySavedByAnotherPageIsIdempotent() {
        DrawingFileEntity before = file("dr1", OWNER, 2);
        DrawingFileEntity latest = file("dr1", OWNER, 3);
        latest.setSceneJson(CHANGED_SCENE);
        when(mapper.findOwned("dr1", OWNER)).thenReturn(before, latest);
        when(mapper.saveScene(eq("dr1"), eq(OWNER), eq(CHANGED_SCENE), eq(2), any(LocalDateTime.class)))
                .thenReturn(0);

        assertThat(service.save("dr1", new SaveDrawingFileRequest(CHANGED_SCENE, 2), OWNER).revision())
                .isEqualTo(3);
        verify(mapper, never()).insertVersion(any(), anyInt(), any(), any());
    }

    @Test
    void listsAndRestoresOwnedVersionsAsNewRevision() {
        DrawingFileEntity current = file("dr1", OWNER, 5);
        DrawingFileEntity restored = file("dr1", OWNER, 6);
        restored.setSceneJson(CHANGED_SCENE);
        DrawingFileVersionEntity version = version("dr1", 3, CHANGED_SCENE);
        when(mapper.findOwned("dr1", OWNER)).thenReturn(current, current, current, restored);
        when(mapper.listVersions("dr1", OWNER)).thenReturn(List.of(version));
        when(mapper.findVersion("dr1", 3, OWNER)).thenReturn(version);
        when(mapper.saveScene(eq("dr1"), eq(OWNER), eq(CHANGED_SCENE), eq(5), any(LocalDateTime.class)))
                .thenReturn(1);

        assertThat(service.versions("dr1", OWNER)).singleElement()
                .satisfies(item -> assertThat(item.revision()).isEqualTo(3));
        assertThat(service.restore("dr1", 3, 5, OWNER).revision()).isEqualTo(6);
        verify(mapper).insertVersion(eq("dr1"), eq(6), eq(CHANGED_SCENE), any(LocalDateTime.class));
        verify(mapper).pruneVersions("dr1");
    }

    @Test
    void anotherMembersHistoricalVersionLooksMissing() {
        when(mapper.findVersion("dr1", 2, OTHER)).thenReturn(null);

        assertThatThrownBy(() -> service.restore("dr1", 2, 4, OTHER))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("草图文件不存在");
    }

    @Test
    void rejectsMalformedOrIncompleteSceneBeforeWriting() {
        when(mapper.findOwned("dr1", OWNER)).thenReturn(file("dr1", OWNER, 1));

        assertThatThrownBy(() -> service.save("dr1", new SaveDrawingFileRequest("{broken", 1), OWNER))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("有效 JSON");
        assertThatThrownBy(() -> service.save(
                "dr1", new SaveDrawingFileRequest("{\"elements\":[]}", 1), OWNER))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("完整");
        verify(mapper, never()).saveScene(any(), any(), any(), anyInt(), any());
    }

    @Test
    void renameAndDeleteCannotTouchAnotherMembersFile() {
        when(mapper.renameOwned(eq("dr1"), eq(OTHER), any(), any())).thenReturn(0);
        when(mapper.deleteOwned("dr1", OTHER)).thenReturn(0);

        assertThatThrownBy(() -> service.rename(
                "dr1", new RenameDrawingFileRequest("别人的草图"), OTHER))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.delete("dr1", OTHER))
                .isInstanceOf(NotFoundException.class);
    }

    private DrawingFileEntity file(String id, String owner, int revision) {
        DrawingFileEntity entity = new DrawingFileEntity();
        entity.setId(id);
        entity.setOwnerMemberId(owner);
        entity.setTitle("架构草图");
        entity.setSceneJson(SCENE);
        entity.setRevision(revision);
        entity.setCreatedAt(LocalDateTime.of(2026, 8, 12, 9, 0));
        entity.setUpdatedAt(LocalDateTime.of(2026, 8, 12, 10, 0));
        return entity;
    }

    private DrawingFileVersionEntity version(String fileId, int revision, String sceneJson) {
        DrawingFileVersionEntity entity = new DrawingFileVersionEntity();
        entity.setFileId(fileId);
        entity.setRevision(revision);
        entity.setSceneJson(sceneJson);
        entity.setCreatedAt(LocalDateTime.of(2026, 8, 12, 10, revision));
        return entity;
    }
    @Test
    void knowledgeManagedDrawingCannotBeDeletedIndependently() {
        var entity = new DrawingFileEntity(); entity.setId("dr1");
        when(mapper.findOwned("dr1", OWNER)).thenReturn(entity);
        when(mapper.linkedDocumentCount("dr1")).thenReturn(1);
        assertThatThrownBy(() -> service.delete("dr1", OWNER)).isInstanceOf(BadRequestException.class).hasMessageContaining("知识库");

        verify(mapper, never()).deleteOwned("dr1", OWNER);
    }

    @Test
    void sharedKnowledgeDrawingRenamesDocumentThroughAuthorizedMetadataFlow() {
        var access = mock(KnowledgeAccessService.class);
        var knowledge = mock(KnowledgeService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "knowledgeAccess", access);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "knowledge", knowledge);
        var entity = file("linked", OWNER, 5); entity.setDocId("doc"); entity.setKbId("kb");
        when(mapper.findKnowledge("linked")).thenReturn(entity);
        when(access.visible("kb", OTHER)).thenReturn(true);
        when(mapper.renameOwned(eq("linked"), eq(OWNER), eq("新名称"), any())).thenReturn(1);
        when(knowledge.updateDoc(eq("doc"), any())).thenAnswer(invocation -> {
            assertThat(new KnowledgeAccessService(null, null).actor()).isEqualTo(OTHER);
            var request = (cn.wayuwa.wao.dto.PatchDocRequest) invocation.getArgument(1);
            assertThat(request.title()).isEqualTo("新名称");
            assertThat(request.content()).isNull();
            assertThat(request.blocks()).isNull();
            entity.setTitle(request.title());
            return null;
        });
        assertThat(service.rename("linked", new RenameDrawingFileRequest("新名称"), OTHER).title()).isEqualTo("新名称");
        verify(knowledge).updateDoc(eq("doc"), any());
        when(access.visible("kb", OTHER)).thenReturn(false);
        assertThatThrownBy(() -> service.rename("linked", new RenameDrawingFileRequest("禁止"), OTHER)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.save("linked", new SaveDrawingFileRequest(CHANGED_SCENE, 5), OTHER)).isInstanceOf(NotFoundException.class);
        verify(mapper, never()).saveScene(any(), any(), any(), anyInt(), any());
    }

    @Test
    void copyRequiresReadAccessAndCreatesIndependentPersonalScene() {
        DrawingFileEntity source = file("dr1", OWNER, 4);
        when(mapper.findOwned("dr1", OWNER)).thenReturn(source);
        var groups = mock(cn.wayuwa.wao.mapper.DrawingGroupMapper.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "groups", groups);
        when(groups.groupOf(OWNER, "dr1")).thenReturn("g1");
        var copied = service.copy("dr1", OWNER);
        verify(groups).assign(OWNER, copied.id(), "g1");
        ArgumentCaptor<DrawingFileEntity> captor = ArgumentCaptor.forClass(DrawingFileEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getOwnerMemberId()).isEqualTo(OWNER);
        assertThat(captor.getValue().getSceneJson()).isEqualTo(source.getSceneJson());
        assertThat(copied.title()).endsWith("副本");
        assertThat(copied.id()).isNotEqualTo(source.getId());
        assertThat(copied.docId()).isNull();
        assertThatThrownBy(() -> service.copy("dr1", OTHER)).isInstanceOf(NotFoundException.class);
    }

}
