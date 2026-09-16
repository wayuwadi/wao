package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Collections;
import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.mapper.NoteTagMapper;
import org.junit.jupiter.api.Test;

class NoteTagServiceTest {
    @Test
    void mergeDeduplicatesAndDeletePreservesDocuments() {
        NoteTagMapper mapper = mock(NoteTagMapper.class);
        NoteTagService service = new NoteTagService(mapper);
        var note = new cn.wayuwa.wao.domain.DocEntity();
        note.setId("note-1");
        note.setTagsJson("[\"旧标签\",\"保留\",\"目标\"]");
        when(mapper.taggedNotes("旧标签")).thenReturn(List.of(note));
        service.replace("旧标签", "目标");
        verify(mapper).updateTags("note-1", "[\"目标\",\"保留\"]");
        verify(mapper).deleteName("旧标签");
        service.replace("旧标签", null);
        verify(mapper).updateTags("note-1", "[\"保留\",\"目标\"]");
        assertThatThrownBy(() -> service.replace("目标", "目标")).isInstanceOf(BadRequestException.class);
    }
    @Test
    void validatesNormalizesAndPersistsNames() {
        NoteTagMapper mapper = mock(NoteTagMapper.class);
        NoteTagService service = new NoteTagService(mapper);
        assertThat(service.create(" 灵感 ")).isEqualTo("灵感");
        verify(mapper).ensureName("灵感");
        assertThat(NoteTagService.normalize(List.of("工作", " 工作 ", "灵感"))).containsExactly("工作", "灵感");
        assertThat(NoteTagService.normalize(List.of())).isEmpty();
        assertThatThrownBy(() -> service.create(" ")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.create("a\nb")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.create("a".repeat(33))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> NoteTagService.normalize(Collections.nCopies(21, "工作"))).isInstanceOf(BadRequestException.class);
    }
}
