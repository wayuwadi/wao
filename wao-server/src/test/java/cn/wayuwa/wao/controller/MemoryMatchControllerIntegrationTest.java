package cn.wayuwa.wao.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/** 从 HTTP 到 H2/MyBatis 的真实纵向链路，验证牌局可恢复且隐藏牌面不会提前下发。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:memorymatchtest;DB_CLOSE_DELAY=-1;MODE=MySQL")
@AutoConfigureMockMvc
@Transactional
@Sql("/db/migration/V47__game_hall_persistence.sql")
class MemoryMatchControllerIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    @Test
    void createsFlipsRestoresAndRestartsAuthoritativeGameThroughHttp() throws Exception {
        JsonNode initial = body(mvc.perform(get("/api/games/memory-match"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(initial.path("cards")).hasSize(16);
        assertThat(initial.path("cards")).allMatch(JsonNode::isNull);

        JsonNode flipped = body(mvc.perform(post("/api/games/memory-match/flip")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"index":0,"revision":0}
                                """))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(flipped.path("revealed").get(0).asInt()).isZero();
        assertThat(flipped.path("cards").get(0).isInt()).isTrue();
        assertThat(flipped.path("cards")).filteredOn(JsonNode::isNull).hasSize(15);

        JsonNode restored = body(mvc.perform(get("/api/games/memory-match"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(restored.path("revision").asInt()).isEqualTo(1);
        assertThat(restored.path("revealed").get(0).asInt()).isZero();

        JsonNode restarted = body(mvc.perform(post("/api/games/memory-match/restart")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"revision":1}
                                """))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(restarted.path("revision").asInt()).isEqualTo(2);
        assertThat(restarted.path("steps").asInt()).isZero();
        assertThat(restarted.path("revealed")).isEmpty();
    }

    private JsonNode body(String response) throws Exception {
        return json.readTree(response).path("data");
    }
}
