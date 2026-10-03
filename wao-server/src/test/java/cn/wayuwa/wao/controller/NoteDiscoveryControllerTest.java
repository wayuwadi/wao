package cn.wayuwa.wao.controller;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.*;
import cn.wayuwa.wao.service.*;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;
class NoteDiscoveryControllerTest {
    NoteDiscoveryService service;CurrentMember member;MockMvc mvc;
    @BeforeEach void setup(){service=mock(NoteDiscoveryService.class);member=mock(CurrentMember.class);mvc=standaloneSetup(new NoteDiscoveryController(service,mock(KnowledgeService.class),member)).setControllerAdvice(new GlobalExceptionHandler()).build();}
    @Test void missingMemberNeverFallsBack() throws Exception {mvc.perform(get("/api/note-discoveries/current")).andExpect(status().isUnauthorized());verifyNoInteractions(service);}
    @Test void requestCannotChooseAnotherMember() throws Exception {when(member.id(any())).thenReturn("own");when(service.current("own")).thenReturn(Map.of("automationState","OFF"));mvc.perform(get("/api/note-discoveries/current").param("memberId","foreign")).andExpect(status().isOk()).andExpect(jsonPath("$.data.automationState").value("OFF"));verify(service).current("own");}
    @Test void invalidFeedbackDoesNotReachService() throws Exception {when(member.id(any())).thenReturn("own");for(String body:new String[]{"{\"expectedVersion\":1.5,\"saved\":true}","{\"expectedVersion\":0,\"memberId\":\"foreign\",\"saved\":true}","{\"saved\":true}"})mvc.perform(patch("/api/note-discoveries/items/a/feedback").contentType("application/json").content(body)).andExpect(status().isBadRequest());verifyNoInteractions(service);}
    @Test void independentFeedbackConflictIs409() throws Exception {when(member.id(any())).thenReturn("own");when(service.feedback(eq("own"),eq("a"),eq(0L),anyMap())).thenThrow(new ConflictException("版本变化"));mvc.perform(patch("/api/note-discoveries/items/a/feedback").contentType("application/json").content("{\"expectedVersion\":0,\"saved\":true}")).andExpect(status().isConflict());}
    @Test void privateSourceDenialIs404() throws Exception {when(member.id(any())).thenReturn("own");doThrow(new NotFoundException("来源不可访问")).when(service).requireSource("own","a","foreign-doc");mvc.perform(get("/api/note-discoveries/items/a/sources/foreign-doc")).andExpect(status().isNotFound());}
    @Test void preferenceNeedsBothFieldsAndNonnegativeVersion() throws Exception {when(member.id(any())).thenReturn("own");for(String body:new String[]{"{}","{\"enabled\":true,\"expectedVersion\":-1}"})mvc.perform(put("/api/note-discoveries/preference").contentType("application/json").content(body)).andExpect(status().isBadRequest());verifyNoInteractions(service);}
}
