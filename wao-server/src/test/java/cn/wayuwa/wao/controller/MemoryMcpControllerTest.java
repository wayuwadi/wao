package cn.wayuwa.wao.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.dto.PersonalMemoryDto;
import cn.wayuwa.wao.service.MemoryAccessTokenService;
import cn.wayuwa.wao.service.PersonalMemoryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class MemoryMcpControllerTest {

    private final MemoryAccessTokenService tokens = mock(MemoryAccessTokenService.class);
    private final PersonalMemoryService memories = mock(PersonalMemoryService.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final MemoryMcpController controller = new MemoryMcpController(tokens, memories, json);
    private final MockHttpServletRequest http = new MockHttpServletRequest("POST", "/api/mcp");

    @BeforeEach
    void authenticate() {
        http.setScheme("https");
        http.setServerName("wao.wayuwa.cn");
        http.setServerPort(443);
        when(tokens.authenticate("valid-token")).thenReturn("wg");
    }

    @Test
    void rejectsMissingBearerTokenWithoutOpeningBrowserSession() throws Exception {
        JsonNode request = json.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
                """);

        var response = controller.handle(null, request, http);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst("WWW-Authenticate")).isEqualTo("Bearer realm=\"wao-memory\"");
        assertThat(response.getBody().path("error").path("message").asText()).contains("令牌无效");
    }

    @Test
    void initializesAndPublishesReadWriteMemoryTools() throws Exception {
        JsonNode initialize = json.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25"}}
                """);
        JsonNode list = json.readTree("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """);

        JsonNode initBody = controller.handle("Bearer valid-token", initialize, http).getBody();
        JsonNode listBody = controller.handle("Bearer valid-token", list, http).getBody();

        assertThat(initBody.path("result").path("protocolVersion").asText()).isEqualTo("2025-11-25");
        assertThat(initBody.path("result").path("instructions").asText()).contains("先调用 search_memory");
        assertThat(listBody.path("result").path("tools").findValuesAsText("name"))
                .containsExactly("search_memory", "save_memory", "memory_source_options");
    }

    @Test
    void searchesOnlyTokenOwnersPersonalMemory() throws Exception {
        when(memories.searchForMcp("wg", "长期规划", "https://wao.wayuwa.cn")).thenReturn("""
                <wao_personal_memory_sources><memory url="https://wao.wayuwa.cn/memory?entry=m1">做 WAO</memory></wao_personal_memory_sources>
                """);
        JsonNode request = json.readTree("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"search_memory","arguments":{"query":"长期规划"}}}
                """);

        JsonNode body = controller.handle("Bearer valid-token", request, http).getBody();

        assertThat(body.path("result").path("content").get(0).path("text").asText())
                .contains("https://wao.wayuwa.cn/memory?entry=m1", "做 WAO");
        verify(memories).searchForMcp("wg", "长期规划", "https://wao.wayuwa.cn");
    }

    @Test
    void savesStableMemoryToTokenOwnersPrivateStore() throws Exception {
        PersonalMemoryDto saved = new PersonalMemoryDto("m1", "主动给体验建议", "每个任务都给建议",
                "preference", "ai", "text", null, null, null,
                java.time.LocalDateTime.now(), java.time.LocalDateTime.now(), null);
        when(memories.createAiText("wg", "主动给体验建议", "每个任务都给建议", "preference"))
                .thenReturn(saved);
        JsonNode request = json.readTree("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"save_memory","arguments":{
                  "title":"主动给体验建议","content":"每个任务都给建议","category":"preference"
                }}}
                """);

        JsonNode body = controller.handle("Bearer valid-token", request, http).getBody();

        verify(memories).createAiText("wg", "主动给体验建议", "每个任务都给建议", "preference");
        assertThat(body.path("result").path("structuredContent").path("url").asText())
                .isEqualTo("https://wao.wayuwa.cn/memory?entry=m1");
    }

    @Test
    void exposesOnlyRealSourceOptions() throws Exception {
        JsonNode request = json.readTree("""
                {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"memory_source_options","arguments":{}}}
                """);

        JsonNode body = controller.handle("Bearer valid-token", request, http).getBody();

        assertThat(body.path("result").path("structuredContent").path("categories").size()).isEqualTo(6);
        assertThat(body.path("result").path("structuredContent").path("mediaTypes").toString())
                .contains("image", "audio", "video");
    }
}
