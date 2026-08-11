package cn.wayuwa.wao.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.wayuwa.wao.agent.domain.PlatformAccountEntity;
import cn.wayuwa.wao.agent.domain.PublishTaskEntity;
import cn.wayuwa.wao.agent.mapper.PlatformAccountMapper;
import cn.wayuwa.wao.agent.mapper.PublishTaskMapper;
import cn.wayuwa.wao.agent.platform.CredentialCipher;
import cn.wayuwa.wao.agent.platform.PlatformCredential;
import cn.wayuwa.wao.agent.service.PlatformAccountService;
import cn.wayuwa.wao.agent.service.PublishTaskService;
import cn.wayuwa.wao.agent.service.SharedAccountService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 话题 → 文案 → 审核 → 发布的纵向链路。抖音开放平台用 JDK 内置 HttpServer 做桩，
 * 走的是真实的 RestClient 调用与真实的状态机，只有平台服务端是本地替身。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PublishFlowIntegrationTest {

    private static HttpServer douyinStub;
    private static final AtomicInteger uploadCalls = new AtomicInteger();
    private static final AtomicInteger createCalls = new AtomicInteger();

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private PublishTaskService publishTasks;
    @Autowired private PlatformAccountMapper accountMapper;
    @Autowired private PublishTaskMapper taskMapper;
    @Autowired private CredentialCipher cipher;
    @Autowired private SharedAccountService accounts;

    @BeforeAll
    static void startStub() throws IOException {
        douyinStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        douyinStub.createContext("/api/douyin/v1/video/upload_video/", exchange -> {
            uploadCalls.incrementAndGet();
            respond(exchange, "{\"data\":{\"error_code\":0,\"video\":{\"video_id\":\"vid-stub\"}}}");
        });
        douyinStub.createContext("/api/douyin/v1/video/create_video/", exchange -> {
            createCalls.incrementAndGet();
            respond(exchange, "{\"data\":{\"error_code\":0,\"item_id\":\"item-stub\"}}");
        });
        douyinStub.start();
    }

    @AfterAll
    static void stopStub() {
        if (douyinStub != null) douyinStub.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @DynamicPropertySource
    static void douyinProperties(DynamicPropertyRegistry registry) {
        registry.add("wao-agent.platform.douyin.client-key", () -> "test-client-key");
        registry.add("wao-agent.platform.douyin.client-secret", () -> "test-client-secret");
        registry.add("wao-agent.platform.douyin.redirect-uri",
                () -> "http://localhost/api/agent/platforms/DOUYIN/callback");
        registry.add("wao-agent.platform.douyin.api-base",
                () -> "http://127.0.0.1:" + douyinStub.getAddress().getPort());
    }

    @Test
    void centerReportsHonestCapabilityPerPlatform() throws Exception {
        mvc.perform(get("/api/agent/publish/center"))
                .andExpect(status().isOk())
                // 抖音已配置凭据，声明可真实发布
                .andExpect(jsonPath("$.data.platforms[0].platform", org.hamcrest.Matchers.equalTo("DOUYIN")))
                .andExpect(jsonPath("$.data.platforms[0].publishSupported", org.hamcrest.Matchers.is(true)))
                .andExpect(jsonPath("$.data.platforms[0].mediaKinds[0]", org.hamcrest.Matchers.equalTo("VIDEO")))
                // 小红书没有官方发布接口，必须声明为不可发布
                .andExpect(jsonPath("$.data.platforms[3].platform", org.hamcrest.Matchers.equalTo("XIAOHONGSHU")))
                .andExpect(jsonPath("$.data.platforms[3].publishSupported", org.hamcrest.Matchers.is(false)))
                .andExpect(jsonPath("$.data.platforms[3].note", org.hamcrest.Matchers.containsString("小红书")))
                // 测试配置没有 API Key，模型状态必须如实为不可用
                .andExpect(jsonPath("$.data.ai.available", org.hamcrest.Matchers.is(false)));
    }

    @Test
    void generationIsRefusedWhenNoModelConfigured() throws Exception {
        mvc.perform(post("/api/agent/publish/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"topic":"夏天露营装备","platform":"DOUYIN","generateCopy":true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("AGENT_AI")));
    }

    @Test
    void platformWithoutOfficialApiRefusesToPublish() throws Exception {
        String taskId = createTask("小红书选题", "XIAOHONGSHU");
        String mediaId = uploadVideo();
        updateTask(taskId, 1, null, mediaId, "正文");

        mvc.perform(post("/api/agent/publish/tasks/" + taskId + "/submit"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("小红书")));
    }

    @Test
    void submitRequiresBoundAccountAndMedia() throws Exception {
        String taskId = createTask("缺账号的任务", "DOUYIN");
        updateTask(taskId, 1, null, null, "正文");

        mvc.perform(post("/api/agent/publish/tasks/" + taskId + "/submit"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("选择要发布到的抖音账号")));

        String accountId = bindDouyinAccount();
        updateTask(taskId, 2, accountId, null, "正文");

        mvc.perform(post("/api/agent/publish/tasks/" + taskId + "/submit"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("需要先上传素材")));
    }

    @Test
    void staleVersionUpdateIsRejected() throws Exception {
        String taskId = createTask("乐观锁校验", "DOUYIN");
        updateTask(taskId, 1, null, null, "第一版");

        mvc.perform(post("/api/agent/publish/tasks/" + taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":1,"title":"过期写入","content":"旧数据"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void fullFlowUploadsToDouyinAndRecordsItemId() throws Exception {
        int uploadsBefore = uploadCalls.get();
        int createsBefore = createCalls.get();

        String taskId = createTask("夏天露营装备", "DOUYIN");
        String accountId = bindDouyinAccount();
        String mediaId = uploadVideo();
        updateTask(taskId, 1, accountId, mediaId, "三件让露营舒适度翻倍的装备");

        mvc.perform(post("/api/agent/publish/tasks/" + taskId + "/submit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", org.hamcrest.Matchers.equalTo("PUBLISHING")));

        PublishTaskEntity pinned = taskMapper.selectById(taskId);
        // 素材落在本节点磁盘，任务必须钉在同一节点上执行
        assertThat(pinned.getNodeId()).isEqualTo("test-node");

        publishTasks.processPublishing();

        PublishTaskEntity done = taskMapper.selectById(taskId);
        assertThat(done.getStatus()).isEqualTo(PublishTaskService.PUBLISHED);
        assertThat(done.getPlatformItemId()).isEqualTo("item-stub");
        assertThat(done.getProgress()).isEqualTo(100);
        assertThat(done.getPublishedAt()).isNotNull();
        assertThat(uploadCalls.get()).isEqualTo(uploadsBefore + 1);
        assertThat(createCalls.get()).isEqualTo(createsBefore + 1);
    }

    @Test
    void publishingIsSkippedByNodesThatDoNotHoldTheMedia() throws Exception {
        String taskId = createTask("跨节点校验", "DOUYIN");
        String accountId = bindDouyinAccount();
        String mediaId = uploadVideo();
        updateTask(taskId, 1, accountId, mediaId, "正文");
        mvc.perform(post("/api/agent/publish/tasks/" + taskId + "/submit")).andExpect(status().isOk());

        // 模拟素材留在另一台节点上
        PublishTaskEntity task = taskMapper.selectById(taskId);
        task.setNodeId("other-node");
        taskMapper.updateById(task);

        publishTasks.processPublishing();

        assertThat(taskMapper.selectById(taskId).getStatus()).isEqualTo(PublishTaskService.PUBLISHING);
    }

    private String createTask(String topic, String platform) throws Exception {
        String body = mvc.perform(post("/api/agent/publish/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"topic":"%s","platform":"%s","generateCopy":false}
                                """.formatted(topic, platform)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", org.hamcrest.Matchers.equalTo("DRAFT")))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data").path("id").asText();
    }

    private void updateTask(String taskId, int version, String accountId, String mediaId, String content)
            throws Exception {
        JsonNode payload = json.createObjectNode()
                .put("version", version)
                .put("title", "标题")
                .put("content", content)
                .put("platformAccountId", accountId)
                .put("mediaAssetId", mediaId)
                .set("tags", json.createArrayNode().add("露营").add("户外"));
        mvc.perform(post("/api/agent/publish/tasks/" + taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andExpect(status().isOk());
    }

    private String uploadVideo() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "clip.mp4", "video/mp4",
                new byte[] {1, 2, 3, 4, 5});
        String body = mvc.perform(multipart("/api/agent/publish/media").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kind", org.hamcrest.Matchers.equalTo("VIDEO")))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data").path("id").asText();
    }

    /** 直接落一条已授权的抖音账号，绕开需要真实用户交互的 OAuth 跳转。 */
    private String bindDouyinAccount() {
        LocalDateTime now = LocalDateTime.now();
        PlatformAccountEntity entity = new PlatformAccountEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setWorkspaceId(accounts.workspaceId("acc-demo"));
        entity.setPlatform("DOUYIN");
        entity.setPlatformUserId("open-" + UUID.randomUUID());
        entity.setDisplayName("抖音测试账号");
        entity.setStatus(PlatformAccountService.STATUS_ACTIVE);
        entity.setScope("user_info,video.create.bind");
        entity.setCredentialCipher(cipher.encrypt(new PlatformCredential(entity.getPlatformUserId(),
                "at-stub", "rt-stub", "video.create.bind", now.plusHours(6), now.plusDays(20))));
        entity.setAccessTokenExpiresAt(now.plusHours(6));
        entity.setRefreshTokenExpiresAt(now.plusDays(20));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        accountMapper.insert(entity);
        return entity.getId();
    }
}
