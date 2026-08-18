package cn.wayuwa.wao.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

/** 从真实 HTTP、校验、Service 到 MyBatis 验证家庭重要日可持久化并按年度展开。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:familydatetest;DB_CLOSE_DELAY=-1;MODE=MySQL")
@AutoConfigureMockMvc
@Sql("/family-important-date-schema.sql")
class FamilyImportantDateControllerIntegrationTest {
    @Autowired private MockMvc mvc;

    @Test
    void persistsAndExpandsCalendarOnlyImportantDateThroughHttp() throws Exception {
        mvc.perform(post("/api/calendar/important-dates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"妈妈生日","eventDate":"1990-05-10","note":"记得提前订花",
                                 "reminderEnabled":false,"reminderDaysBefore":0,"reminderTime":null}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("妈妈生日"));

        mvc.perform(get("/api/calendar/important-dates")
                        .queryParam("from", "2026-05-01").queryParam("to", "2026-05-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].eventDate").value("1990-05-10"))
                .andExpect(jsonPath("$.data.events[0].date").value("2026-05-10"))
                .andExpect(jsonPath("$.data.events[0].name").value("妈妈生日"))
                .andExpect(jsonPath("$.data.reminderChannelAvailable").value(false))
                .andExpect(jsonPath("$.data.reminderChannelHint").value(
                        org.hamcrest.Matchers.containsString("尚未绑定飞书")));
    }
}
