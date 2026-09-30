package cn.wayuwa.wao.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.dto.DocDto;
import cn.wayuwa.wao.dto.PatchDocRequest;
import cn.wayuwa.wao.dto.RichDocContentDto;
import cn.wayuwa.wao.service.DocPresenceService;
import cn.wayuwa.wao.service.KnowledgeService;
import cn.wayuwa.wao.service.NoteAiWorkflowService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class DocControllerNoteAiTest {
    @Test void savesBodyBeforeSchedulingAndDoesNotMisreportAiFailureAsSaveFailure() {
        KnowledgeService knowledge = mock(KnowledgeService.class);
        NoteAiWorkflowService ai = mock(NoteAiWorkflowService.class);
        CurrentMember member = mock(CurrentMember.class);
        HttpServletRequest http = mock(HttpServletRequest.class);
        DocController controller = new DocController(knowledge, mock(DocPresenceService.class), member, ai);
        PatchDocRequest request = new PatchDocRequest(null, null,
                new RichDocContentDto(1, null, "最新续写"), null, null, 3);
        DocDto saved = new DocDto("d1", "标题", "m1", "", "", false, List.of(), List.of(),
                request.content(), List.of(), KnowledgeService.NOTES_KB_ID, null, 4);
        when(knowledge.updateDoc("d1", request)).thenReturn(saved);
        when(member.id(http)).thenReturn("m1");
        doThrow(new IllegalStateException("队列暂时不可用")).when(ai).refreshAfterSave("m1", "d1");
        var result = controller.patch("d1", request, http);
        assertThat(result.data()).isEqualTo(saved);
        assertThat(result.code()).isZero();
        InOrder order = inOrder(knowledge, ai);
        order.verify(knowledge).updateDoc("d1", request);
        order.verify(ai).refreshAfterSave("m1", "d1");
    }
}
