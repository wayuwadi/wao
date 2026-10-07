package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.dto.DocDto;
import cn.wayuwa.wao.dto.FeishuChatSyncConfigDto;
import cn.wayuwa.wao.feishu.FeishuApiException;
import cn.wayuwa.wao.mapper.FeishuDailyArchiveMapper;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** 隔离 H2 上验证持久日期领取及真实事务提交，不连接共享库。 */
class FeishuDailyArchiveServiceTest {
    private static final LocalDateTime ENABLED_AT = LocalDateTime.of(2026, 10, 7, 23, 59);
    private static final LocalDateTime MIDNIGHT = LocalDateTime.of(2026, 10, 8, 0, 0);
    private FeishuDailyArchiveMapper db;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private FeishuDailyArchiveService service;
    private final FeishuChatSyncService archive = mock(FeishuChatSyncService.class);

    @BeforeEach
    void setUp() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        manager = new DataSourceTransactionManager(source);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE automation_job (id VARCHAR(32) PRIMARY KEY,name VARCHAR(128),icon VARCHAR(64),
                schedule_label VARCHAR(64),status VARCHAR(16),description VARCHAR(512),source VARCHAR(128),
                target VARCHAR(128),steps_json JSON,handler VARCHAR(64),next_fire_at TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE automation_run (id VARCHAR(32) PRIMARY KEY,job_id VARCHAR(32),run_time_label VARCHAR(64),
                run_at TIMESTAMP,result VARCHAR(64),duration_ms INT,ord INT)
                """);
        var configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true); configuration.addMapper(FeishuDailyArchiveMapper.class);
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(configuration);
        db = new SqlSessionTemplate(factory.getObject()).getMapper(FeishuDailyArchiveMapper.class);
        service = new FeishuDailyArchiveService(db, archive, manager);
        when(archive.config()).thenReturn(new FeishuChatSyncConfigDto("app", "***", true, "chat", "群聊", "kb", List.of()));
        var doc = mock(DocDto.class); when(doc.id()).thenReturn("doc-real-result");
        when(archive.archivePreviousDay(any(), eq("chat"), eq("kb"), any()))
                .thenReturn(new FeishuChatSyncService.ChatSyncDayResult(MIDNIGHT.toLocalDate().minusDays(1),
                        "chat", "群聊", "kb", doc, List.of()));
    }

    @Test
    void readDoesNotCreateJobsAndEnableWaitsForNextMidnightWithCas() {
        assertThat(service.state().enabled()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM automation_job", Integer.class)).isZero();
        service.configure(true, false, ENABLED_AT);
        assertThat(service.state().nextRunAt()).isEqualTo(MIDNIGHT);
        service.configure(true, true, MIDNIGHT.plusHours(1));
        assertThat(service.state().nextRunAt()).isEqualTo(MIDNIGHT);
        assertThatThrownBy(() -> service.configure(false, false, ENABLED_AT)).isInstanceOf(ConflictException.class);
        assertThat(service.state().enabled()).isTrue();
        verify(archive, never()).archivePreviousDay(any(), any(), any(), any());
    }

    @Test
    void disabledAndDaytimeRunsNeverReadFeishu() {
        service.runMidnight(MIDNIGHT);
        service.configure(true, false, ENABLED_AT);
        service.runMidnight(ENABLED_AT);
        service.runMidnight(MIDNIGHT.plusMinutes(1));
        assertThat(db.latestRun()).isNull();
        verify(archive, never()).archivePreviousDay(any(), any(), any(), any());
    }

    @Test
    void twoNodesClaimOnceAndCommitEvidenceBeforeExternalRead() throws Exception {
        service.configure(true, false, ENABLED_AT);
        var node2 = new FeishuDailyArchiveService(db, archive, manager);
        when(archive.archivePreviousDay(any(), any(), any(), any())).thenAnswer(call -> {
            assertThat(jdbc.queryForObject("SELECT result FROM automation_run", String.class)).isEqualTo("RUNNING");
            var doc = mock(DocDto.class); when(doc.id()).thenReturn("doc-real-result");
            return new FeishuChatSyncService.ChatSyncDayResult(LocalDate.of(2026, 10, 7), "chat", "群聊", "kb", doc, List.of());
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var first = executor.submit(() -> { await(start); service.runMidnight(MIDNIGHT); });
            var second = executor.submit(() -> { await(start); node2.runMidnight(MIDNIGHT); });
            start.countDown(); first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        service.runMidnight(MIDNIGHT.plusSeconds(20));
        verify(archive, times(1)).archivePreviousDay(eq(MIDNIGHT.toLocalDate()), eq("chat"), eq("kb"), any());
        assertThat(service.state().latestRun().archiveDate()).isEqualTo(LocalDate.of(2026, 10, 7));
        assertThat(service.state().latestRun().status()).isEqualTo("SUCCESS");
        assertThat(service.state().latestRun().docId()).isEqualTo("doc-real-result");
        assertThat(service.state().nextRunAt()).isEqualTo(MIDNIGHT.plusDays(1));
    }

    @Test
    void failedFetchPersistsSafeEvidenceAndCannotBeReclaimedSameDay() {
        service.configure(true, false, ENABLED_AT);
        when(archive.archivePreviousDay(any(), any(), any(), any()))
                .thenThrow(new FeishuApiException("external secret content must not persist"));
        service.runMidnight(MIDNIGHT);
        db.configure("正常", MIDNIGHT); // 即便错误地重置 nextFire，日期主键也不能重领。
        service.runMidnight(MIDNIGHT);
        assertThat(service.state().latestRun().status()).isEqualTo("FAILED");
        assertThat(service.state().latestRun().errorCode()).isEqualTo("FEISHU_API_FAILED");
        assertThat(db.latestRun().getResult()).doesNotContain("secret");
        verify(archive, times(1)).archivePreviousDay(any(), any(), any(), any());
    }

    @Test
    void crashClaimIsNotReclaimedAndMissedDatesAreNotBackfilled() {
        service.configure(true, false, ENABLED_AT.minusDays(5));
        db.claim("fda20261007", "2026-10-07", MIDNIGHT);
        service.runMidnight(MIDNIGHT);
        assertThat(service.state().latestRun().status()).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM automation_run", Integer.class)).isEqualTo(1);
        verify(archive, never()).archivePreviousDay(any(), any(), any(), any());
        service.runMidnight(MIDNIGHT.plusDays(1));
        verify(archive).archivePreviousDay(eq(MIDNIGHT.plusDays(1).toLocalDate()), any(), any(), any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM automation_run", Integer.class)).isEqualTo(2);
    }

    @Test
    void outerTransactionRollbackDoesNotEraseClaimOrAllowAnotherFetch() {
        service.configure(true, false, ENABLED_AT);
        new TransactionTemplate(manager).executeWithoutResult(tx -> { service.runMidnight(MIDNIGHT); tx.setRollbackOnly(); });
        service.runMidnight(MIDNIGHT);
        assertThat(db.latestRun()).isNotNull();
        verify(archive, times(1)).archivePreviousDay(any(), any(), any(), any());
    }

    @Test
    void disablingAfterClaimStopsWriteAndPreservesRefusal() {
        service.configure(true, false, ENABLED_AT);
        when(archive.archivePreviousDay(any(), any(), any(), any())).thenAnswer(call -> {
            service.configure(false, true, MIDNIGHT);
            call.<Runnable>getArgument(3).run();
            throw new AssertionError("disabled guard should refuse");
        });
        service.runMidnight(MIDNIGHT);
        assertThat(service.state().latestRun().errorCode()).isEqualTo("ARCHIVE_DISABLED");
        assertThat(service.state().nextRunAt()).isNull();
    }

    @Test
    void incompleteTargetCannotBeEnabled() {
        when(archive.config()).thenReturn(new FeishuChatSyncConfigDto("app", "", false, "chat", "群聊", "", List.of()));
        assertThatThrownBy(() -> service.configure(true, false, ENABLED_AT)).isInstanceOf(BadRequestException.class);
        assertThat(db.job()).isNull();
    }

    private static void await(CountDownLatch start) {
        try { start.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
    }
}
