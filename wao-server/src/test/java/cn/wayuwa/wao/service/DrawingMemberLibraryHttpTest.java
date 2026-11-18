package cn.wayuwa.wao.service;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.GlobalExceptionHandler;
import cn.wayuwa.wao.controller.DrawingMemberLibraryController;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:drawing_member_library;MODE=MySQL;DB_CLOSE_DELAY=-1")
class DrawingMemberLibraryHttpTest {
    private static final String URL = "/api/drawings/library";
    private static final String MEMBER = "X-Test-Member";
    @Autowired JdbcTemplate db;
    @Autowired DrawingMemberLibraryService library;
    private MockMvc mvc;

    @BeforeEach void setup() {
        db.execute("CREATE TABLE IF NOT EXISTS drawing_member_library(member_id VARCHAR(32) PRIMARY KEY, items_json LONGTEXT NOT NULL)");
        db.update("DELETE FROM drawing_member_library");
        var current = mock(CurrentMember.class);
        when(current.id(any())).thenAnswer(invocation -> invocation.<HttpServletRequest>getArgument(0).getHeader(MEMBER));
        mvc = MockMvcBuilders.standaloneSetup(new DrawingMemberLibraryController(current, library))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test void devicesShareAdditionsAndRemovalsOnlyWithinTheSameMember() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mvc.perform(patch(URL).contentType(MediaType.APPLICATION_JSON).content(change("x")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(URL).header(MEMBER, "owner"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(patch(URL).header(MEMBER, "owner").contentType(MediaType.APPLICATION_JSON).content(change("network")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value("network"));
        mvc.perform(patch(URL).header(MEMBER, "owner").contentType(MediaType.APPLICATION_JSON).content(change("local")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(patch(URL).header(MEMBER, "owner").contentType(MediaType.APPLICATION_JSON).content(change("local")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(get(URL).header(MEMBER, "other"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(patch(URL).header(MEMBER, "other").contentType(MediaType.APPLICATION_JSON)
                .content("{\"upsert\":[],\"remove\":[\"network\"]}")).andExpect(status().isOk());
        mvc.perform(get(URL).header(MEMBER, "owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(patch(URL).header(MEMBER, "owner").contentType(MediaType.APPLICATION_JSON)
                .content("{\"upsert\":[],\"remove\":[\"network\"]}")).andExpect(status().isOk());
        mvc.perform(get(URL).header(MEMBER, "owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value("local"));
    }

    private static String change(String id) {
        return """
                {"upsert":[{"id":"%s","status":"published","elements":[{"id":"shape","type":"rectangle"}]}],"remove":[]}
                """.formatted(id);
    }
}
