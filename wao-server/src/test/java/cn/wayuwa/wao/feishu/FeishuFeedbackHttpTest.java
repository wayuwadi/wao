package cn.wayuwa.wao.feishu;

import static org.assertj.core.api.Assertions.assertThat;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** 真实 HTTP 请求工厂必须支持 PATCH；HttpURLConnection 工厂会在发出请求前失败。 */
class FeishuFeedbackHttpTest {
    @Test void productionHttpFactorySendsPatch() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var method = new AtomicReference<String>();
        var payload = new AtomicReference<String>();
        server.createContext("/open-apis/im/v1/messages/om_one", exchange -> {
            method.set(exchange.getRequestMethod());
            payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"code\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var client = new FeishuOpenApiClient(RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort());
            client.updateFeedbackCard("test-token", "om_one", client.feedbackCard("提醒", "正文", "n1", "✅ <at id=ou_one></at>：做到了"));
            assertThat(method.get()).isEqualTo("PATCH");
            assertThat(payload.get()).contains("update_multi", "授权个人表情", "做到了");
        } finally { server.stop(0); }
    }
}
