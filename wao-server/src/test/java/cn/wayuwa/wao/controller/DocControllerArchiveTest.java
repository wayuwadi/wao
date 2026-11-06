package cn.wayuwa.wao.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.service.DocPresenceService;
import cn.wayuwa.wao.service.KnowledgeService;
import cn.wayuwa.wao.service.NoteAiWorkflowService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DocControllerArchiveTest {
    @Test
    void catalogIsAnOptInResponseAndExistingClientsKeepFullArchives() throws Exception {
        KnowledgeService knowledge = mock(KnowledgeService.class);
        when(knowledge.listArchivedDocs("k1")).thenReturn(List.of());
        when(knowledge.listArchivedDocCatalog("k1")).thenReturn(List.of());
        var mvc = MockMvcBuilders.standaloneSetup(new DocController(knowledge,
                mock(DocPresenceService.class), mock(CurrentMember.class), mock(NoteAiWorkflowService.class))).build();

        mvc.perform(get("/api/docs/archived").param("kbId", "k1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray());
        verify(knowledge).listArchivedDocs("k1");
        verify(knowledge, never()).listArchivedDocCatalog(anyString());

        mvc.perform(get("/api/docs/archived").param("kbId", "k1").param("catalog", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray());
        verify(knowledge).listArchivedDocCatalog("k1");

        mvc.perform(get("/api/docs/archived").param("kbId", "k1").param("catalog", "false"))
                .andExpect(status().isOk());
        verify(knowledge, times(2)).listArchivedDocs("k1");
    }
}
