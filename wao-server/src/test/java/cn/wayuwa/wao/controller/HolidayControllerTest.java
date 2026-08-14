package cn.wayuwa.wao.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.GlobalExceptionHandler;
import cn.wayuwa.wao.dto.HolidayCalendarDto;
import cn.wayuwa.wao.dto.HolidayEventDto;
import cn.wayuwa.wao.dto.HolidayReminderDto;
import cn.wayuwa.wao.dto.HolidaySettingsDto;
import cn.wayuwa.wao.service.HolidayCalendarService;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HolidayController.class)
@Import(GlobalExceptionHandler.class)
class HolidayControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private HolidayCalendarService service;
    @MockBean private CurrentMember currentMember;

    @BeforeEach
    void currentMember() {
        when(currentMember.idOr(any(), eq("wg"))).thenReturn("wg");
    }

    @Test
    void returnsMemberFilteredHolidayCalendarInApiEnvelope() throws Exception {
        HolidayEventDto event = new HolidayEventDto("holiday-x", "international.mothers-day",
                "2026-05-10", 2026, "母亲节", "INTERNATIONAL", "FESTIVAL", "FAMILY",
                "WAO 稳定公历规则", null, true, false, null);
        when(service.calendar("wg", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31)))
                .thenReturn(new HolidayCalendarDto(List.of(event), List.of()));

        mockMvc.perform(get("/api/calendar/holidays")
                        .queryParam("from", "2026-05-01").queryParam("to", "2026-05-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.events[0].name").value("母亲节"));
    }

    @Test
    void savesInternationalScopeAndReminder() throws Exception {
        when(service.saveSettings(eq("wg"), any())).thenReturn(
                new HolidaySettingsDto(List.of("FAMILY"), List.of(), true, "提醒将私聊飞书"));
        when(service.saveReminder(eq("wg"), eq("international.mothers-day"), any()))
                .thenReturn(new HolidayReminderDto("international.mothers-day", true, "08:30"));

        mockMvc.perform(put("/api/calendar/holidays/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selectedInternationalCategories\":[\"FAMILY\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.selectedInternationalCategories[0]").value("FAMILY"));
        mockMvc.perform(put("/api/calendar/holidays/international.mothers-day/reminder")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"reminderTime\":\"08:30\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reminderTime").value("08:30"));
    }

    @Test
    void rejectsInvalidOrOversizedDateWindows() throws Exception {
        mockMvc.perform(get("/api/calendar/holidays")
                        .queryParam("from", "2026-05-31").queryParam("to", "2026-05-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("to 不能早于 from"));
        mockMvc.perform(get("/api/calendar/holidays")
                        .queryParam("from", "2026-01-01").queryParam("to", "2027-02-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("查询区间过长"));
    }
}
