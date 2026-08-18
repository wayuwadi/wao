package cn.wayuwa.wao.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.GlobalExceptionHandler;
import cn.wayuwa.wao.dto.FamilyImportantDateCalendarDto;
import cn.wayuwa.wao.dto.FamilyImportantDateDto;
import cn.wayuwa.wao.service.FamilyImportantDateService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FamilyImportantDateController.class)
@Import(GlobalExceptionHandler.class)
class FamilyImportantDateControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private FamilyImportantDateService service;
    @MockBean private CurrentMember currentMember;

    @BeforeEach
    void currentMember() {
        when(currentMember.idOr(any(), eq("wg"))).thenReturn("member-a");
    }

    @Test
    void readsAndCreatesOnlyForSessionMember() throws Exception {
        when(service.calendar("member-a", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31)))
                .thenReturn(new FamilyImportantDateCalendarDto(List.of(), List.of(), true, "提醒将私聊飞书"));
        when(service.create(eq("member-a"), any())).thenReturn(new FamilyImportantDateDto(
                "fid1", "妈妈生日", "1990-05-10", "记得提前订花", true, 3, "08:30"));

        mockMvc.perform(get("/api/calendar/important-dates")
                        .queryParam("from", "2026-05-01").queryParam("to", "2026-05-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reminderChannelAvailable").value(true));
        mockMvc.perform(post("/api/calendar/important-dates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"妈妈生日","eventDate":"1990-05-10","note":"记得提前订花",
                                 "reminderEnabled":true,"reminderDaysBefore":3,"reminderTime":"08:30"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("fid1"))
                .andExpect(jsonPath("$.data.reminderDaysBefore").value(3));
        verify(service).create(eq("member-a"), any());
    }

    @Test
    void validatesFieldsAndDateWindowAndScopesDelete() throws Exception {
        mockMvc.perform(post("/api/calendar/important-dates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":" ","eventDate":"2026-05-10","reminderEnabled":false,
                                 "reminderDaysBefore":31}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/calendar/important-dates")
                        .queryParam("from", "2026-06-01").queryParam("to", "2026-05-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("to 不能早于 from"));
        mockMvc.perform(delete("/api/calendar/important-dates/fid1"))
                .andExpect(status().isOk());
        verify(service).delete("member-a", "fid1");
    }
}
