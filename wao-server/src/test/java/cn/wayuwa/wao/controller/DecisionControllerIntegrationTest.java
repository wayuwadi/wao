package cn.wayuwa.wao.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.wayuwa.wao.auth.CurrentMember;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

/** 决策专属接口只能写入和读取当前成员的个人决策。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:decisioncontroller;DB_CLOSE_DELAY=-1;MODE=MySQL")
@AutoConfigureMockMvc
@Sql({"/project-memory-schema.sql", "/unified-memory-schema.sql"})
class DecisionControllerIntegrationTest {
    @Autowired private MockMvc mvc;
    @MockBean private CurrentMember currentMember;

    @BeforeEach
    void authenticate() {
        when(currentMember.id(any(HttpServletRequest.class))).thenReturn("member-a");
    }

    @Test
    void ignoresClientScopeAndKeepsRevisionConflictProtection() throws Exception {
        String created = mvc.perform(post("/api/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scopeType":"project","scopeId":"other-project","category":"convention",
                                 "title":"保留决策","content":"决策正文"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scopeType").value("personal"))
                .andExpect(jsonPath("$.data.scopeId").value("member-a"))
                .andExpect(jsonPath("$.data.category").value("decision"))
                .andReturn().getResponse().getContentAsString();
        var data = new com.fasterxml.jackson.databind.ObjectMapper().readTree(created).path("data");
        String id = data.path("id").asText();
        String revisionId = data.path("currentPublishedRevisionId").asText();

        mvc.perform(get("/api/decisions")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(post("/api/decisions/{id}/revisions", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scopeType":"personal","category":"decision","title":"更新决策",
                                 "content":"更新正文","baseRevisionId":"%s"}
                                """.formatted(revisionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision.title").value("更新决策"));
        mvc.perform(post("/api/decisions/{id}/revisions", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scopeType":"personal","category":"decision","title":"旧版本覆盖",
                                 "content":"不应保存","baseRevisionId":"%s"}
                                """.formatted(revisionId)))
                .andExpect(status().isConflict());

        when(currentMember.id(any(HttpServletRequest.class))).thenReturn("member-b");
        mvc.perform(get("/api/decisions/{id}", id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/decisions")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }
}
