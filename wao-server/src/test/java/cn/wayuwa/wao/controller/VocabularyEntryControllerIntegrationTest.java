package cn.wayuwa.wao.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import cn.wayuwa.wao.dto.CaptureVocabularyEntryRequest;
import cn.wayuwa.wao.service.VocabularyEntryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** 从 HTTP 到数据库验证个人单词本的真实读写闭环。 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Sql("/db/migration/V57__personal_vocabulary_notebook.sql")
class VocabularyEntryControllerIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private VocabularyEntryService vocabulary;

    @Test
    void capturesDeduplicatesStudiesAndDeletesThroughHttp() throws Exception {
        String first = mvc.perform(post("/api/vocabulary")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"term\":\"resilient\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value(true))
                .andExpect(jsonPath("$.data.entry.encounterCount").value(1))
                .andReturn().getResponse().getContentAsString();
        JsonNode body = json.readTree(first);
        String id = body.path("data").path("entry").path("id").asText();

        mvc.perform(post("/api/vocabulary")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"term\":\"  Resilient  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value(false))
                .andExpect(jsonPath("$.data.entry.id").value(id))
                .andExpect(jsonPath("$.data.entry.encounterCount").value(2));

        mvc.perform(patch("/api/vocabulary/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"meaning\":\"韧性的\",\"context\":\"a resilient system\",\"state\":\"LEARNING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.meaning").value("韧性的"));

        mvc.perform(post("/api/vocabulary/{id}/reviews", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"MASTERED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewCount").value(1))
                .andExpect(jsonPath("$.data.state").value("MASTERED"));

        vocabulary.capture(new CaptureVocabularyEntryRequest("isolated"), "another-member");

        mvc.perform(get("/api/vocabulary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].encounterCount").value(2))
                .andExpect(jsonPath("$.data[0].context").value("a resilient system"));

        mvc.perform(delete("/api/vocabulary/{id}", id))
                .andExpect(status().isOk());
        mvc.perform(get("/api/vocabulary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        assertThat(vocabulary.list("another-member"))
                .singleElement()
                .satisfies(entry -> assertThat(entry.term()).isEqualTo("isolated"));
    }
}
