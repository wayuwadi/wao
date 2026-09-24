package cn.wayuwa.wao.controller;

import static org.assertj.core.api.Assertions.assertThat;

import cn.wayuwa.wao.service.WorkspaceMcpTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.jdbc.Sql;

/** 真实 HTTP 链路覆盖初始化、工具发现、只读隔离与立即吊销。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:workspacemcpnetwork;DB_CLOSE_DELAY=-1;MODE=MySQL")
@Sql("/memory-mcp-schema.sql")
class WorkspaceMcpNetworkIntegrationTest {
    @Autowired private WorkspaceMcpTokenService tokens;
    @Autowired private ObjectMapper json;
    @LocalServerPort private int port;

    @Test
    void connectsWithDedicatedBearerAndRevocationImmediatelyStopsAccess() throws Exception {
        var readOnly = tokens.create("wd", "网络验收只读设备", false);
        var writable = tokens.create("wd", "网络验收读写设备", true);
        JsonNode initialized = body(post(readOnly.secret(), """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}
                """));
        assertThat(initialized.path("result").path("serverInfo").path("name").asText())
                .isEqualTo("wao-workspace");

        String discover = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";
        JsonNode readTools = body(post(readOnly.secret(), discover));
        assertThat(readTools.path("result").path("tools").findValuesAsText("name"))
                .contains("list_open_tasks", "get_document")
                .doesNotContain("create_task", "update_document", "update_subtask_status");
        JsonNode writeTools = body(post(writable.secret(), discover));
        assertThat(writeTools.path("result").path("tools").findValuesAsText("name"))
                .contains("create_task", "update_document", "create_project", "update_subtask_status");
        JsonNode knowledge = body(post(readOnly.secret(), """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_knowledge_bases","arguments":{}}}
                """));
        assertThat(knowledge.path("result").path("isError").asBoolean()).isFalse();
        assertThat(knowledge.path("result").path("structuredContent").path("data").findValuesAsText("id"))
                .contains("kb-memory");
        JsonNode denied = body(post(readOnly.secret(), """
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"create_project","arguments":{"name":"不应创建"}}}
                """));
        assertThat(denied.path("result").path("isError").asBoolean()).isTrue();
        assertThat(post("wao_mcp_old-memory-secret", discover).statusCode()).isEqualTo(401);
        assertThat(post(null, discover).statusCode()).isEqualTo(401);

        tokens.revoke("wd", readOnly.token().id());
        assertThat(post(readOnly.secret(), discover).statusCode()).isEqualTo(401);
        assertThat(post(writable.secret(), discover).statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> post(String secret, String payload) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/mcp/workspace"))
                .header("Content-Type", "application/json");
        if (secret != null) request.header("Authorization", "Bearer " + secret);
        return HttpClient.newHttpClient().send(request.POST(HttpRequest.BodyPublishers.ofString(payload)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }
}
