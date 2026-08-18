package cn.wayuwa.wao.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import cn.wayuwa.wao.mapper.PersonalMemoryMapper;
import cn.wayuwa.wao.service.MemoryAccessTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.jdbc.Sql;

/** 通过真实随机端口验证 Codex 的 MCP 初始化、发现、写入与吊销链路。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:memorymcpnetwork;DB_CLOSE_DELAY=-1;MODE=MySQL")
@Sql("/memory-mcp-schema.sql")
class MemoryMcpNetworkIntegrationTest {

    @Autowired private TestRestTemplate http;
    @Autowired private MemoryAccessTokenService tokens;
    @Autowired private PersonalMemoryMapper memories;
    @Autowired private ObjectMapper json;
    @LocalServerPort private int port;

    @Test
    void completesNetworkHandshakeWriteAndImmediateRevocation() throws Exception {
        var created = tokens.create("wg", "集成测试设备");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(created.secret());
        headers.setContentType(MediaType.APPLICATION_JSON);

        JsonNode initialized = post(headers, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"codex","version":"test"}}}
                """);
        assertThat(initialized.path("result").path("serverInfo").path("name").asText())
                .isEqualTo("wao-personal-memory");

        JsonNode tools = post(headers, """
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """);
        assertThat(tools.path("result").path("tools").findValuesAsText("name"))
                .containsExactly("search_memory", "save_memory", "memory_source_options");

        JsonNode saved = post(headers, """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"save_memory","arguments":{
                  "title":"跨设备职责","content":"负责 WAO 的产品与交付。","category":"responsibility"
                }}}
                """);
        String memoryId = saved.path("result").path("structuredContent").path("id").asText();
        assertThat(memories.selectById(memoryId).getContent()).contains("负责 WAO 的产品与交付");
        assertThat(memories.selectById(memoryId).getMemberId()).isEqualTo("wg");

        JsonNode searched = post(headers, """
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"search_memory","arguments":{
                  "query":"WAO 产品交付"
                }}}
                """);
        assertThat(searched.path("result").path("content").get(0).path("text").asText())
                .contains("跨设备职责", "/memory?entry=" + memoryId);

        tokens.revoke("wg", created.token().id());
        HttpResponse<String> revoked = HttpClient.newHttpClient().send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/mcp"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + created.secret())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"ping\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(revoked.statusCode()).isEqualTo(401);
        assertThat(revoked.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE))
                .contains("Bearer realm=\"wao-memory\"");
    }

    private JsonNode post(HttpHeaders headers, String body) throws Exception {
        ResponseEntity<String> response = http.exchange("/api/mcp", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return json.readTree(response.getBody());
    }
}
