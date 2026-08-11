package cn.wayuwa.wao.agent.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class AgentSessionManagerTest {

    @Test
    void readsTheSameAccountAttributeWrittenByWao() {
        AgentSessionManager manager = new AgentSessionManager(new SharedSessionProperties(""));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute("wao.accountId", "acc-wg");

        assertThat(manager.currentAccountId(request)).isEqualTo("acc-wg");
    }

    @Test
    void returnsNullWithoutWaoSessionInProductionConfiguration() {
        AgentSessionManager manager = new AgentSessionManager(new SharedSessionProperties(""));

        assertThat(manager.currentAccountId(new MockHttpServletRequest())).isNull();
    }
}
