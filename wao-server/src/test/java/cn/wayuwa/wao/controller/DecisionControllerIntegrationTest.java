package cn.wayuwa.wao.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** 决策下线后旧客户端不能继续读写；不删除共享记忆数据。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:decisionretirement;DB_CLOSE_DELAY=-1;MODE=MySQL")
@AutoConfigureMockMvc
class DecisionControllerIntegrationTest {
    @Autowired private MockMvc mvc;
    @Test void retiredRoutesCannotReadOrWrite() throws Exception {
        mvc.perform(get("/api/decisions")).andExpect(status().isNotFound());
        mvc.perform(post("/api/decisions")).andExpect(status().isNotFound());
        mvc.perform(post("/api/decisions/old/revisions")).andExpect(status().isNotFound());
        mvc.perform(post("/api/decisions/old/archive")).andExpect(status().isNotFound());
    }
}
