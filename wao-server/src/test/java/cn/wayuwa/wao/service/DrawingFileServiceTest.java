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

    private static final String OWNER = "wd";
    private static final String OTHER = "wg";
    private static final String SCENE = "{\"type\":\"excalidraw\",\"elements\":[],\"appState\":{},\"files\":{}}";
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
        when(mapper.saveScene(eq("dr1"), eq(OWNER), eq(SCENE), eq(2), any(LocalDateTime.class)))
                .thenReturn(1);

        var saved = service.save("dr1", new SaveDrawingFileRequest(SCENE, 2), OWNER);

        assertThat(saved.revision()).isEqualTo(3);
        verify(mapper).saveScene(eq("dr1"), eq(OWNER), eq(SCENE), eq(2), any(LocalDateTime.class));
    }

    @Test
    void acceptsOfficialSerializationWithoutBinaryFilesField() {
        String officialScene = "{\"type\":\"excalidraw\",\"elements\":[],\"appState\":{}}";
        DrawingFileEntity before = file("dr1", OWNER, 2);
        DrawingFileEntity after = file("dr1", OWNER, 3);
        after.setSceneJson(officialScene);
        when(mapper.findOwned("dr1", OWNER)).thenReturn(before, after);
        when(mapper.saveScene(eq("dr1"), eq(OWNER), eq(officialScene), eq(2), any(LocalDateTime.class)))
                .thenReturn(1);

        assertThat(service.save("dr1", new SaveDrawingFileRequest(officialScene, 2), OWNER).sceneJson())
                .isEqualTo(officialScene);
    }

    @Test
    void staleRevisionNeverOverwritesNewerScene() {
        DrawingFileEntity current = file("dr1", OWNER, 4);
        when(mapper.findOwned("dr1", OWNER)).thenReturn(current);
        when(mapper.saveScene(eq("dr1"), eq(OWNER), eq(SCENE), anyInt(), any(LocalDateTime.class)))
                .thenReturn(0);

        assertThatThrownBy(() -> service.save("dr1", new SaveDrawingFileRequest(SCENE, 2), OWNER))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("草稿已保留");
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
}
