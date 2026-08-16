package cn.wayuwa.wao.agent.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.ByteArrayOutputStream;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenAiVideoClientTest {

    @Test
    void createsRetrievesAndStreamsOfficialVideoWithoutExposingApiKey() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        VideoGenerationProperties properties = new VideoGenerationProperties(
                "https://api.openai.test/v1", "secret-key", "sora-2", 30_000, 15_000L);
        OpenAiVideoClient client = new OpenAiVideoClient(properties, builder.build());

        server.expect(requestTo("https://api.openai.test/v1/videos"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer secret-key"))
                .andExpect(header("Idempotency-Key", "local-task-1"))
                .andExpect(header("Content-Type", Matchers.startsWith("multipart/form-data;boundary=")))
                .andExpect(content().string(Matchers.containsString("sora-2")))
                .andExpect(content().string(Matchers.containsString("1280x720")))
                .andRespond(withSuccess("""
                        {"id":"video-1","status":"queued","progress":0}
                        """, MediaType.APPLICATION_JSON));

        server.expect(requestTo("https://api.openai.test/v1/videos/video-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id":"video-1","status":"completed","progress":100}
                        """, MediaType.APPLICATION_JSON));
        byte[] mp4 = new byte[] {0, 0, 0, 4, 102, 116, 121, 112};
        server.expect(requestTo("https://api.openai.test/v1/videos/video-1/content"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(mp4, MediaType.valueOf("video/mp4")));

        OpenAiVideoClient.RemoteVideo created = client.create(
                "沙漠公路跟拍", "sora-2", 4, "1280x720", "local-task-1");
        assertThat(created.id()).isEqualTo("video-1");
        assertThat(created.status()).isEqualTo("queued");
        assertThat(client.retrieve("video-1").progress()).isEqualTo(100);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        client.download("video-1", output, 1024);
        assertThat(output.toByteArray()).containsExactly(mp4);
        server.verify();
    }
}
