package cn.wayuwa.wao.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.service.ProjectService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

/** 从真实 HTTP、ApiResponse、校验到 H2 表验证项目记忆 CRUD。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:projectmemorycontroller;DB_CLOSE_DELAY=-1;MODE=MySQL")
@AutoConfigureMockMvc
@Sql({"/project-memory-schema.sql", "/unified-memory-schema.sql"})
class ProjectMemoryControllerIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private ProjectService projects;
    @MockBean private CurrentMember currentMember;

    @BeforeEach
    void authenticateProjectMember() {
        when(currentMember.idOr(any(HttpServletRequest.class), eq("wg"))).thenReturn("wg");
        when(currentMember.id(any(HttpServletRequest.class))).thenReturn("wg");
    }

    @Test
    void createsListsUpdatesAndDeletesThroughApiResponse() throws Exception {
        String response = mvc.perform(post("/api/projects/p1/memories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category":"decision","title":"数据库决策","content":"继续使用 MySQL 9。",
                                 "evidenceLinks":["https://github.com/acme/wao/pull/1"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.category").value("decision"))
                .andReturn().getResponse().getContentAsString();
        String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("data").path("id").asText();

        mvc.perform(get("/api/projects/p1/memories").param("query", "MySQL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        publishLatestDraft(id);
        mvc.perform(get("/api/projects/p1/memories").param("query", "MySQL"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));

        mvc.perform(patch("/api/projects/p1/memories/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category":"convention","title":"数据库约定","content":"migration 只允许新增。",
                                 "evidenceLinks":["https://github.com/acme/wao/pull/1"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.category").value("convention"));

        mvc.perform(get("/api/projects/p1/memories").param("query", "migration"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        publishLatestDraft(id);
        mvc.perform(get("/api/projects/p1/memories").param("query", "migration"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));

        mvc.perform(delete("/api/projects/p1/memories/{id}", id)).andExpect(status().isOk());
        mvc.perform(get("/api/projects/p1/memories"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
    }

    private void publishLatestDraft(String memoryId) throws Exception {
        String revisionId = jdbc.queryForObject("SELECT id FROM memory_revision WHERE memory_id = ? AND state = 'draft' ORDER BY version_no DESC LIMIT 1",
                String.class, memoryId);
        mvc.perform(post("/api/memory-revisions/{id}/publish", revisionId)).andExpect(status().isOk());
    }
}
