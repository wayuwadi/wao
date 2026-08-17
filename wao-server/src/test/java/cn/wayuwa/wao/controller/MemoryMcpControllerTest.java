package cn.wayuwa.wao.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import cn.wayuwa.wao.dto.AssistantSourceOptionsDto;
import cn.wayuwa.wao.dto.DocBlockDto;
import cn.wayuwa.wao.dto.DocDto;
import cn.wayuwa.wao.service.AiAssistantAssetService;
import cn.wayuwa.wao.service.KnowledgeService;
import cn.wayuwa.wao.service.MemoryAccessTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class MemoryMcpControllerTest {

    private final MemoryAccessTokenService tokens = mock(MemoryAccessTokenService.class);
    private final AiAssistantAssetService assets = mock(AiAssistantAssetService.class);
    private final KnowledgeService knowledge = mock(KnowledgeService.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final MemoryMcpController controller = new MemoryMcpController(tokens, assets, knowledge, json);
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
    void searchesAssetsAndReturnsAbsoluteTraceableLinks() throws Exception {
        when(assets.relevantAssets(eq("长期规划"), any())).thenReturn("""
                <wao_asset_sources><source url="/kb/k1?doc=d1">做 WAO</source></wao_asset_sources>
                """);
        JsonNode request = json.readTree("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"search_memory","arguments":{"query":"长期规划"}}}
                """);

        JsonNode body = controller.handle("Bearer valid-token", request, http).getBody();

        assertThat(body.path("result").path("content").get(0).path("text").asText())
                .contains("https://wao.wayuwa.cn/kb/k1?doc=d1", "做 WAO");
    }

    @Test
    void savesStableMemoryAsARealKnowledgeDocument() throws Exception {
        DocDto saved = new DocDto("m1", "主动给体验建议", "wg", null, null, false,
                List.of(), List.of(new DocBlockDto(null, null, "每个任务都给建议", null, null)),
                List.of(), "kb-memory", "kb-memory-f", 1);
        when(knowledge.createSharedMemory(any(), any(), any(), any())).thenReturn(saved);
        JsonNode request = json.readTree("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"save_memory","arguments":{
                  "title":"主动给体验建议","content":"每个任务都给建议","category":"preference"
                }}}
                """);

        JsonNode body = controller.handle("Bearer valid-token", request, http).getBody();

        verify(knowledge).createSharedMemory("主动给体验建议", "每个任务都给建议", "preference", "wg");
        assertThat(body.path("result").path("structuredContent").path("url").asText())
                .isEqualTo("https://wao.wayuwa.cn/kb/kb-memory?doc=m1");
    }

    @Test
    void exposesOnlyRealSourceOptions() throws Exception {
        when(assets.sourceOptions()).thenReturn(new AssistantSourceOptionsDto(
                List.of(), List.of("娃哥"), List.of(), null, null));
        JsonNode request = json.readTree("""
                {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"memory_source_options","arguments":{}}}
                """);

        JsonNode body = controller.handle("Bearer valid-token", request, http).getBody();

        assertThat(body.path("result").path("structuredContent").path("senders").get(0).asText()).isEqualTo("娃哥");
    }
}
