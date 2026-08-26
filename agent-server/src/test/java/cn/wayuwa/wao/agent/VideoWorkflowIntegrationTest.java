package cn.wayuwa.wao.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.wayuwa.wao.agent.mapper.VideoProjectMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** 从默认分镜、乐观锁保存、真实文件上传到 Range 回放的完整用户链路。 */
@SpringBootTest
@AutoConfigureMockMvc
class VideoWorkflowIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private VideoProjectMapper projects;

    @BeforeEach
    void clean() {
        projects.delete(Wrappers.emptyWrapper());
    }

    @Test
    void defaultProjectIsExactlyThreeMinutesAndPersistsEditsWithOptimisticLock() throws Exception {
        MvcResult initial = mvc.perform(get("/api/agent/video-workflow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title", Matchers.equalTo("三分钟认识 WAO")))
                .andExpect(jsonPath("$.data.scenes.length()", Matchers.equalTo(8)))
                .andExpect(jsonPath("$.data.totalSeconds", Matchers.equalTo(180)))
                .andExpect(jsonPath("$.data.status", Matchers.equalTo("DRAFT")))
                .andReturn();

        JsonNode data = json.readTree(initial.getResponse().getContentAsString()).path("data");
        String payload = json.createObjectNode()
                .put("version", data.path("version").asInt())
                .put("title", "WAO 三分钟功能导览")
                .set("scenes", data.path("scenes"))
                .toString();

        mvc.perform(post("/api/agent/video-workflow")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version", Matchers.equalTo(2)))
                .andExpect(jsonPath("$.data.title", Matchers.equalTo("WAO 三分钟功能导览")));

        mvc.perform(post("/api/agent/video-workflow")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", Matchers.containsString("其他页面")));
    }

    @Test
    void uploadedWebmIsMarkedExportedAndSupportsAuthenticatedRangePlayback() throws Exception {
        mvc.perform(get("/api/agent/video-workflow")).andExpect(status().isOk());
        byte[] bytes = new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 'w', 'e', 'b', 'm'};
        MockMultipartFile file = new MockMultipartFile("file", "wao-intro.webm", "video/webm", bytes);
        MvcResult upload = mvc.perform(multipart("/api/agent/video-workflow/media").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.byteSize", Matchers.equalTo(bytes.length)))
                .andReturn();
        String mediaId = json.readTree(upload.getResponse().getContentAsString()).path("data").path("id").asText();

        mvc.perform(post("/api/agent/video-workflow/export")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"mediaAssetId\":\"" + mediaId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", Matchers.equalTo("EXPORTED")))
                .andExpect(jsonPath("$.data.outputUrl", Matchers.equalTo("/api/agent/video-workflow/output")));

        MvcResult range = mvc.perform(get("/api/agent/video-workflow/output")
                        .header(HttpHeaders.RANGE, "bytes=4-7"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 4-7/" + bytes.length))
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andReturn();
        assertThat(range.getResponse().getContentAsByteArray()).isEqualTo("webm".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void workflowUploadRejectsNonWebmInsteadOfRelabelingIt() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "picture.png", "image/png", new byte[] {1, 2});
        mvc.perform(multipart("/api/agent/video-workflow/media").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", Matchers.containsString("WebM")));

        MockMultipartFile relabeled = new MockMultipartFile(
                "file", "fake.webm", "video/webm", "not-a-video".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/agent/video-workflow/media").file(relabeled))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", Matchers.containsString("不能只修改文件类型")));
    }
}
