package cn.wayuwa.wao.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.jdbc.Sql;

/** 从真实 HTTP 控制器到节假日历法引擎验证无需外部配置的读取链路。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:holidaycalendartest;DB_CLOSE_DELAY=-1;MODE=MySQL")
@AutoConfigureMockMvc
@Sql("/holiday-calendar-schema.sql")
class HolidayControllerIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void returnsMothersDayAndOfficialChineseScheduleThroughHttp() throws Exception {
        mvc.perform(get("/api/calendar/holidays")
                        .queryParam("from", "2026-05-01")
                        .queryParam("to", "2026-05-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.events[?(@.name == '母亲节')].date").value("2026-05-10"))
                .andExpect(jsonPath("$.data.events[?(@.name == '劳动节')].dayType").value("DAY_OFF"))
                .andExpect(jsonPath("$.data.events[?(@.name == '劳动节调休上班')].date").value("2026-05-09"))
                .andExpect(jsonPath("$.data.years[0].officialScheduleAvailable").value(true))
                .andExpect(jsonPath("$.data.years[0].sourceUrl").value(org.hamcrest.Matchers.containsString("gov.cn")));
    }
}
