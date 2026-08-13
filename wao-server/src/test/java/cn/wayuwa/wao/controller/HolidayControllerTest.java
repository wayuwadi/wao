package cn.wayuwa.wao.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import cn.wayuwa.wao.common.GlobalExceptionHandler;
import cn.wayuwa.wao.dto.HolidayEventDto;
import cn.wayuwa.wao.service.HolidayService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HolidayController.class)
@Import(GlobalExceptionHandler.class)
class HolidayControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private HolidayService holidayService;

    @Test
    void returnsHolidayEventsInApiEnvelope() throws Exception {
        when(holidayService.events(eq(LocalDate.of(2026, 5, 1)), eq(LocalDate.of(2026, 5, 31))))
                .thenReturn(List.of(new HolidayEventDto(
                        "holiday-2026-05-10-x", "2026-05-10", "母亲节", "INTERNATIONAL", "FESTIVAL")));

        mockMvc.perform(get("/api/calendar/holidays")
                        .queryParam("from", "2026-05-01")
                        .queryParam("to", "2026-05-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].name").value("母亲节"));
    }

    @Test
    void rejectsInvalidOrOversizedDateWindows() throws Exception {
        mockMvc.perform(get("/api/calendar/holidays")
                        .queryParam("from", "2026-05-31")
                        .queryParam("to", "2026-05-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("to 不能早于 from"));

        mockMvc.perform(get("/api/calendar/holidays")
                        .queryParam("from", "2026-01-01")
                        .queryParam("to", "2027-02-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("查询区间过长"));
    }
}
