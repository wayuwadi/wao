package cn.wayuwa.wao.controller;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.wayuwa.wao.auth.CurrentMember;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
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

/** 从真实 HTTP 到数据库验证工作空间保存、内网链接与成员隔离。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:personalworkspacetest;DB_CLOSE_DELAY=-1;MODE=MySQL")
@AutoConfigureMockMvc
@Sql("/personal-workspace-schema.sql")
class PersonalWorkspaceControllerIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private CurrentMember currentMember;

    @BeforeEach
    void authenticatedAsFirstMember() {
        when(currentMember.idOr(any(HttpServletRequest.class), any(String.class))).thenReturn("wg");
    }

    @Test
    void createsSavesReloadsAndNeverLeaksAnotherMembersWorkspace() throws Exception {
        String created = mvc.perform(post("/api/workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"能良工作","description":"常用环境与后台","projectId":null}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("能良工作"))
                .andExpect(jsonPath("$.data.groups[0].name").value("常用"))
                .andExpect(jsonPath("$.data.groups[0].links.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(created)
                .path("data").path("id").asText();

        mvc.perform(put("/api/workspaces/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"能良工作","description":"常用环境与后台","projectId":null,
                                  "groups":[{"id":"wg1","name":"测试环境","icon":"ri-server-line","order":99,
                                    "links":[{"id":"wl1","title":"Nacos 测试环境","url":"http://192.168.1.8:8848/nacos",
                                      "description":"服务发现","environment":"测试","icon":"ri-links-line","order":88,"favorite":true}]}]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups[0].order").value(0))
                .andExpect(jsonPath("$.data.groups[0].links[0].url").value("http://192.168.1.8:8848/nacos"))
                .andExpect(jsonPath("$.data.groups[0].links[0].favorite").value(true));

        mvc.perform(get("/api/workspaces/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups[0].links[0].title").value("Nacos 测试环境"));

        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO personal_workspace
                (id, owner_member_id, project_id, name, description_text, content_json, created_at, updated_at)
                VALUES (?, ?, NULL, ?, '', '{"version":1,"groups":[]}', ?, ?)
                """, "ws-other", "wd", "娃弟的工作空间", now, now);

        mvc.perform(get("/api/workspaces"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[*].name").value(not(hasItem("娃弟的工作空间"))));
        mvc.perform(get("/api/workspaces/ws-other"))
                .andExpect(status().isNotFound());
    }
}
