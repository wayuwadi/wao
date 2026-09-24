package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.dto.DocDto;
import cn.wayuwa.wao.dto.RichDocContentDto;
import cn.wayuwa.wao.dto.SaveNotePolishVersionRequest;
import cn.wayuwa.wao.mapper.NotePolishVersionMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotePolishVersionServiceTest {
    private final KnowledgeService knowledge = mock(KnowledgeService.class);
    private final NotePolishVersionMapper versions = mock(NotePolishVersionMapper.class);
    private final NotePolishVersionService service = new NotePolishVersionService(knowledge, versions);

    @Test
    void storesAlternateVersionWithoutChangingOriginalDoc() {
        when(knowledge.getDoc("note-1")).thenReturn(note("原文"));

        var result = service.save("member-1", "note-1", new SaveNotePolishVersionRequest("原文", "润色版"));

        assertThat(result.sourceMarkdown()).isEqualTo("原文");
        assertThat(result.polishedMarkdown()).isEqualTo("润色版");
        verify(versions).upsert(new NotePolishVersionMapper.Version("note-1", "member-1", "原文", "润色版"));
    }

    @Test
    void rejectsStaleResultAfterUserChangesOriginal() {
        when(knowledge.getDoc("note-1")).thenReturn(note("用户新原文"));

        assertThatThrownBy(() -> service.save("member-1", "note-1",
                new SaveNotePolishVersionRequest("旧原文", "旧润色结果")))
                .isInstanceOf(ConflictException.class);
        verify(versions, never()).upsert(any());
    }

    private static DocDto note(String markdown) {
        return new DocDto("note-1", "标题", "member-1", "", "", false,
                List.of(), List.of(), new RichDocContentDto(1, null, markdown), List.of(),
                KnowledgeService.NOTES_KB_ID, "folder", 1);
    }
}
