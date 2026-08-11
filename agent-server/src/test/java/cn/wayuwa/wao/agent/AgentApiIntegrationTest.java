package cn.wayuwa.wao.agent;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AgentApiIntegrationTest {
    @Autowired private MockMvc mvc;

    @Test
    void publicHealthEndpointSupportsGatewayDeploymentChecks() throws Exception {
        mvc.perform(get("/api/agent/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", equalTo("UP")));
    }

    @Test
    void sharedAccountCreatesWorkspaceAndReturnsSession() throws Exception {
        mvc.perform(get("/api/agent/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountId", equalTo("acc-demo")))
                .andExpect(jsonPath("$.data.displayName", equalTo("本地演示用户")))
                .andExpect(jsonPath("$.data.workspaceId", notNullValue()));
    }

}
