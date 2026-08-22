package cn.wayuwa.wao.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import cn.wayuwa.wao.auth.CurrentMember;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

/** 从真实 HTTP 到数据库验证多模态持久化与成员隔离。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:personalmemorytest;DB_CLOSE_DELAY=-1;MODE=MySQL")
@AutoConfigureMockMvc
@Sql({"/personal-memory-schema.sql", "/unified-memory-schema.sql"})
class PersonalMemoryControllerIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private CurrentMember currentMember;

    @BeforeEach
    void authenticatedAsFirstMember() {
        org.mockito.Mockito.when(currentMember.id(org.mockito.ArgumentMatchers.any(HttpServletRequest.class)))
                .thenReturn("wg");
    }

    @Test
    void persistsTextAndMultimodalFileAndHidesAnotherMember() throws Exception {
        mvc.perform(post("/api/memory/entries").contentType(MediaType.APPLICATION_JSON).content("""
                {"title":"晨跑习惯","content":"喜欢日出前跑步","category":"preference"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.captureMode").value("manual"))
                .andExpect(jsonPath("$.data.mediaType").value("text"));

        byte[] image = "real-image-bytes".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "海边.png", "image/png", image);
        String response = mvc.perform(multipart("/api/memory/entries/upload")
                        .file(file).param("title", "海边旅行").param("content", "夏天")
                        .param("category", "experience"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mediaType").value("image"))
                .andReturn().getResponse().getContentAsString();
        String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response)
                .path("data").path("id").asText();

        mvc.perform(get("/api/memory/entries/{id}/attachment", id))
                .andExpect(status().isOk()).andExpect(content().bytes(image));

        jdbc.update("""
                INSERT INTO personal_memory
                (id, member_id, title, content, category, capture_mode, media_type, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "mem-other", "f", "另一个人的秘密", "不能串台", "context", "manual", "text",
                LocalDateTime.now(), LocalDateTime.now());

        mvc.perform(get("/api/memory/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[*].title").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem("另一个人的秘密"))));
        mvc.perform(get("/api/memory/entries/mem-other/attachment"))
                .andExpect(status().isNotFound());
    }
}
