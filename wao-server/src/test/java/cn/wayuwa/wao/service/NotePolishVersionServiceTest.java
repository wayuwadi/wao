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

    @Test void oldClientCannotInheritEarlierVerification() {
        when(knowledge.getDoc("note-1")).thenReturn(note("原文"));service.save("member-1","note-1",new SaveNotePolishVersionRequest("原文","旧客户端润色"));
        verify(versions).markVerified("note-1","member-1",null,null,null,null);
    }
    @Test void clientCannotInventVerifiedAiOrigin() {
        var jobs=mock(TextPolishJobService.class);var mapper=mock(cn.wayuwa.wao.mapper.TextPolishJobMapper.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"jobs",jobs);org.springframework.test.util.ReflectionTestUtils.setField(service,"jobMapper",mapper);
        when(knowledge.getDoc("note-1")).thenReturn(note("原文"));
        assertThatThrownBy(()->service.save("member-1","note-1",new SaveNotePolishVersionRequest("原文","伪造润色","foreign-job"))).isInstanceOf(cn.wayuwa.wao.common.BadRequestException.class);
        verify(versions,never()).upsert(any());
    }
    @Test void verifiesOwnedCompletedJobAndExactOutput() {
        var jobs=mock(TextPolishJobService.class);var mapper=mock(cn.wayuwa.wao.mapper.TextPolishJobMapper.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"jobs",jobs);org.springframework.test.util.ReflectionTestUtils.setField(service,"jobMapper",mapper);
        when(knowledge.getDoc("note-1")).thenReturn(note("原文"));when(mapper.findOwned("pj1","member-1")).thenReturn(new cn.wayuwa.wao.mapper.TextPolishJobMapper.Job("pj1","member-1","NOTE_BODY","原文","","run",null));
        when(jobs.get("member-1","pj1")).thenReturn(new TextPolishJobService.Result("pj1","SUCCEEDED","","真实润色",null,null));
        assertThatThrownBy(()->service.save("member-1","note-1",new SaveNotePolishVersionRequest("原文","篡改润色","pj1"))).isInstanceOf(cn.wayuwa.wao.common.BadRequestException.class);
        service.save("member-1","note-1",new SaveNotePolishVersionRequest("原文","真实润色","pj1"));verify(versions).markVerified("note-1","member-1","pj1",NoteDiscoveryIndexService.hash("原文"),NoteDiscoveryIndexService.hash("真实润色"),1);
    }
    private static DocDto note(String markdown) {
        return new DocDto("note-1", "标题", "member-1", "", "", false,
                List.of(), List.of(), new RichDocContentDto(1, null, markdown), List.of(),
                KnowledgeService.NOTES_KB_ID, "folder", 1);
    }
}
