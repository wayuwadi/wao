package cn.wayuwa.wao.agent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.wayuwa.wao.agent.mapper.VideoGenerationMapper;
import cn.wayuwa.wao.agent.service.VideoGenerationService;
import cn.wayuwa.wao.agent.video.OpenAiVideoClient;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.io.OutputStream;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "wao-agent.video.base-url=https://api.openai.test/v1",
        "wao-agent.video.api-key=test-key",
        "wao-agent.video.model=sora-2",
        "wao-agent.video.poll-delay-ms=600000"
})
@AutoConfigureMockMvc
class VideoGenerationFlowIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private VideoGenerationMapper videoMapper;
    @Autowired private VideoGenerationService videos;
    @MockBean private OpenAiVideoClient client;

    @BeforeEach
    void clean() {
        videoMapper.delete(Wrappers.emptyWrapper());
    }

    @Test
    void persistsPollsDownloadsAndServesARealCompletedVideo() throws Exception {
        byte[] mp4 = new byte[] {0, 0, 0, 4, 102, 116, 121, 112, 1, 2, 3};
        when(client.create(anyString(), eq("sora-2"), eq(4), eq("1280x720"), anyString()))
                .thenReturn(new OpenAiVideoClient.RemoteVideo("video-real-1", "queued", 0, null));
        when(client.retrieve("video-real-1"))
                .thenReturn(new OpenAiVideoClient.RemoteVideo("video-real-1", "completed", 100, null));
        doAnswer(invocation -> {
            OutputStream output = invocation.getArgument(1);
            output.write(mp4);
            return null;
        }).when(client).download(eq("video-real-1"), any(OutputStream.class), anyLong());

        String response = mvc.perform(post("/api/agent/videos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prompt":"沙漠公路跟拍","model":"sora-2","seconds":4,"size":"1280x720"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", Matchers.equalTo("QUEUED")))
                .andReturn().getResponse().getContentAsString();
        String taskId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response)
                .path("data").path("id").asText();

        videos.processPending();

        mvc.perform(get("/api/agent/videos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tasks[0].status", Matchers.equalTo("COMPLETED")))
                .andExpect(jsonPath("$.data.tasks[0].contentUrl",
                        Matchers.equalTo("/api/agent/videos/" + taskId + "/content")));
        mvc.perform(get("/api/agent/videos/" + taskId + "/content"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(content().contentType("video/mp4"))
                .andExpect(content().bytes(mp4));
        mvc.perform(get("/api/agent/videos/" + taskId + "/content")
                        .header(HttpHeaders.RANGE, "bytes=4-7"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 4-7/11"))
                .andExpect(content().bytes(new byte[] {102, 116, 121, 112}));
    }

    @Test
    void failedSubmissionKeepsParametersAndRetriesWithANewIdempotencyAttempt() throws Exception {
        when(client.create(anyString(), anyString(), anyInt(), anyString(), anyString()))
                .thenReturn(new OpenAiVideoClient.RemoteVideo("video-rejected", "failed", 0, "内容不符合要求"))
                .thenReturn(new OpenAiVideoClient.RemoteVideo("video-retry", "queued", 0, null));

        String response = mvc.perform(post("/api/agent/videos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prompt":"原始提示词","model":"sora-2","seconds":8,"size":"720x1280"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", Matchers.equalTo("FAILED")))
                .andExpect(jsonPath("$.data.errorMessage", Matchers.containsString("内容不符合要求")))
                .andReturn().getResponse().getContentAsString();
        String taskId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response)
                .path("data").path("id").asText();

        mvc.perform(post("/api/agent/videos/" + taskId + "/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", Matchers.equalTo("QUEUED")))
                .andExpect(jsonPath("$.data.attempt", Matchers.equalTo(2)));
        verify(client).create("原始提示词", "sora-2", 8, "720x1280", taskId + "-2");
    }
}
