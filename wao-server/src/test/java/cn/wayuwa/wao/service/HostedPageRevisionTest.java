package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.GlobalExceptionHandler;
import cn.wayuwa.wao.controller.HostedPageController;
import cn.wayuwa.wao.mapper.HostedPageMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Real migrated schema and mapper writes, including competing sessions and the HTTP contract. */
class HostedPageRevisionTest {
    private JdbcTemplate db;
    private SqlSessionFactory sessions;
    private SqlSession session;
    private HostedPageMapper mapper;
    private HostedPageService service;

    @BeforeEach void database() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        db = new JdbcTemplate(source);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V51__hosted_html_page.sql")).execute(source);
        db.execute("ALTER TABLE hosted_page ADD COLUMN source_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL_UPLOAD'");
        db.execute("ALTER TABLE hosted_page ADD COLUMN project_id VARCHAR(64)");
        // Insert before migration: rollout must preserve all old content and initialize its version.
        db.update("INSERT INTO hosted_page(id,slug,title,file_name,html_content,icon_content,icon_content_type,created_by,created_at,updated_at) "
                + "VALUES ('page','stable','原始标题','old.html',?,?, 'image/png','member',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                "original".getBytes(StandardCharsets.UTF_8), new byte[]{1, 2});
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V207__hosted_page_revision.sql")).execute(source);
        var configuration = new Configuration(new Environment("test", new JdbcTransactionFactory(), source));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(HostedPageMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(configuration);
        session = sessions.openSession(true);
        mapper = session.getMapper(HostedPageMapper.class);
        service = new HostedPageService(mapper);
    }

    @AfterEach void close() { if (session != null) session.close(); }

    @Test void migrationPreservesOldRowsAndMetadataReadsIncludeTheVersionWithoutBodies() {
        var metadata = mapper.findMetaById("page");
        assertThat(metadata.getRevision()).isZero();
        assertThat(metadata.getHtmlContent()).isNull();
        assertThat(metadata.getIconContent()).isNull();
        assertThat(db.queryForObject("SELECT html_content FROM hosted_page WHERE id='page'", byte[].class))
                .isEqualTo("original".getBytes(StandardCharsets.UTF_8));
        assertThat(service.list("ALL").get(0).revision()).isZero();
    }

    @Test void competingSessionsWithTheSameRevisionHaveExactlyOneWinner() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var outcomes = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (String title : java.util.List.of("窗口一", "窗口二")) {
                outcomes.add(workers.submit(() -> {
                    try (var competing = sessions.openSession(true)) {
                        var writer = new HostedPageService(competing.getMapper(HostedPageMapper.class));
                        ready.countDown();
                        if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                        try { writer.update("page", null, "stable", title, null, 0L); return true; }
                        catch (ConflictException expected) { return false; }
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(java.util.List.of(outcomes.get(0).get(10, TimeUnit.SECONDS), outcomes.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(db.queryForObject("SELECT revision FROM hosted_page WHERE id='page'", Long.class)).isEqualTo(1L);
        } finally { start.countDown(); workers.shutdownNow(); }
    }

    @Test void legacyWritesAdvanceTheRevisionAndMetadataEditsDoNotRestoreOldBodies() {
        assertThat(service.replace("page", html("new.html", "new body")).revision()).isEqualTo(1);
        assertThatThrownBy(() -> service.update("page", null, "stable", "过时标题", null, 0L))
                .isInstanceOf(ConflictException.class);
        var updated = service.update("page", null, "new-slug", "新标题", null);
        assertThat(updated.revision()).isEqualTo(2);
        assertThat(updated.fileName()).isEqualTo("new.html");
        assertThat(updated.hasIcon()).isTrue();
        assertThat(db.queryForObject("SELECT html_content FROM hosted_page WHERE id='page'", byte[].class))
                .isEqualTo("new body".getBytes(StandardCharsets.UTF_8));
    }

    @Test void contentReplacementPreservesMetadataCommittedAfterItsOldSnapshot() {
        var oldSnapshot = mapper.findMetaById("page");
        service.update("page", null, "latest-slug", "最新标题", null, 0L);
        oldSnapshot.setHtmlContent("replacement".getBytes(StandardCharsets.UTF_8));
        oldSnapshot.setFileName("replacement.html");
        assertThat(mapper.replaceContent(oldSnapshot, 0L)).isZero();
        assertThat(mapper.replaceContent(oldSnapshot, null)).isEqualTo(1);
        var latest = mapper.findMetaById("page");
        assertThat(latest.getTitle()).isEqualTo("最新标题");
        assertThat(latest.getSlug()).isEqualTo("latest-slug");
        assertThat(latest.getRevision()).isEqualTo(2);
    }

    @Test void bothAiReportWritersAdvanceRevisionAndKeepHumanMetadata() {
        db.update("UPDATE hosted_page SET id='run-report-run1',project_id='human-project',source_type='AI_RUN_REPORT'");
        var first = service.saveRunReport("run1", html("report.html", "AI first"), "不应覆盖", "member", "other-project");
        assertThat(first.revision()).isEqualTo(1);
        assertThat(first.title()).isEqualTo("原始标题");
        assertThat(first.projectId()).isEqualTo("human-project");
        assertThatThrownBy(() -> service.replace(first.id(), html("local.html", "stale"), 0L))
                .isInstanceOf(ConflictException.class);
        var second = service.saveGeneratedReport("run1", "AI second".getBytes(StandardCharsets.UTF_8), "仍不覆盖", "member");
        assertThat(second.revision()).isEqualTo(2);
        assertThat(second.slug()).isEqualTo("stable");
        assertThat(second.hasIcon()).isTrue();
        assertThat(db.queryForObject("SELECT icon_content FROM hosted_page", byte[].class)).containsExactly((byte) 1, (byte) 2);
    }

    @Test void aiReportWithAnOldSnapshotPreservesConcurrentlyChangedTitleSlugIconAndProject() {
        var oldSnapshot = mapper.findMetaById("page");
        var newIcon = new MockMultipartFile("icon", "new.png", "image/png", new byte[]{8, 9});
        service.update("page", null, "human-slug", "人工更新", newIcon, 0L);
        db.update("UPDATE hosted_page SET project_id='latest-project' WHERE id='page'");
        oldSnapshot.setHtmlContent("new report".getBytes(StandardCharsets.UTF_8));
        oldSnapshot.setFileName("report.html");
        oldSnapshot.setProjectId("stale-project");
        assertThat(mapper.replaceReportContent(oldSnapshot)).isEqualTo(1);
        var latest = mapper.findMetaById("page");
        assertThat(latest.getSlug()).isEqualTo("human-slug");
        assertThat(latest.getTitle()).isEqualTo("人工更新");
        assertThat(latest.getProjectId()).isEqualTo("latest-project");
        assertThat(latest.getRevision()).isEqualTo(2);
        assertThat(db.queryForObject("SELECT icon_content FROM hosted_page WHERE id='page'", byte[].class))
                .containsExactly((byte) 8, (byte) 9);
    }

    @Test void httpReturnsLatestVersionConflictsAndDeletedRowsWithoutBreakingLegacyUploads() throws Exception {
        var member = mock(CurrentMember.class);
        when(member.id(any())).thenReturn("member");
        var http = MockMvcBuilders.standaloneSetup(new HostedPageController(service, member,
                        mock(ProjectService.class), mock(WorkspaceMcpTokenService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        http.perform(multipart(HttpMethod.PUT, "/api/hosted-pages/page").param("slug", "stable").param("title", "新标题").param("expectedRevision", "0"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(1));
        http.perform(multipart(HttpMethod.PUT, "/api/hosted-pages/page").param("slug", "stale").param("title", "过时").param("expectedRevision", "0"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(40900));
        http.perform(multipart(HttpMethod.PUT, "/api/hosted-pages/page/content").file(html("new.html", "stale")).param("expectedRevision", "0"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(40900));
        http.perform(multipart(HttpMethod.PUT, "/api/hosted-pages/page/content").file(html("new.html", "legacy")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(2));
        db.update("DELETE FROM hosted_page WHERE id='page'");
        session.clearCache();
        http.perform(multipart(HttpMethod.PUT, "/api/hosted-pages/page/content").file(html("new.html", "gone")).param("expectedRevision", "2"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(40400));
    }

    @Test void negativeVersionIsRejectedBeforeAnyWrite() throws Exception {
        var http = MockMvcBuilders.standaloneSetup(new HostedPageController(service, mock(CurrentMember.class),
                        mock(ProjectService.class), mock(WorkspaceMcpTokenService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        http.perform(multipart(HttpMethod.PUT, "/api/hosted-pages/page/content").file(html("new.html", "invalid")).param("expectedRevision", "-1"))
                .andExpect(status().isBadRequest());
        assertThat(mapper.findMetaById("page").getRevision()).isZero();
    }

    private static MockMultipartFile html(String name, String content) {
        return new MockMultipartFile("html", name, "text/html", content.getBytes(StandardCharsets.UTF_8));
    }
}
