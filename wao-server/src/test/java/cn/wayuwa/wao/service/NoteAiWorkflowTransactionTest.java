package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.DocEntity;
import cn.wayuwa.wao.dto.DocDto;
import cn.wayuwa.wao.dto.RichDocContentDto;
import cn.wayuwa.wao.mapper.DocMapper;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import cn.wayuwa.wao.mapper.NoteAiWorkflowMapper;
import cn.wayuwa.wao.mapper.NoteAiWorkflowMapper.Workflow;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** 只在进程内 H2 注入失败，真实事务与 mapper 验证回滚；不连接共享数据库或调用模型。 */
class NoteAiWorkflowTransactionTest {
    @Test void laterStageFailureRollsBackEarlierEnqueueThenRecordsOnlyFailedStage() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE note_ai_workflow (
                  doc_id VARCHAR(64) PRIMARY KEY, member_id VARCHAR(64), base_rev INT,
                  original_title VARCHAR(255), source_markdown_hash VARCHAR(64),
                  title_status VARCHAR(16), title_job_id VARCHAR(64), image_status VARCHAR(16),
                  image_run_id VARCHAR(64), polish_status VARCHAR(16), polish_job_id VARCHAR(64),
                  last_error VARCHAR(500), created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),
                  updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6))
                """);
        jdbc.execute("CREATE TABLE queue_probe (id VARCHAR(64) PRIMARY KEY)");
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(NoteAiWorkflowMapper.class);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(ds); factory.setConfiguration(configuration);
        var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(NoteAiWorkflowMapper.class);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
        String body = "合成验收：周末整理书桌，保留三本常用笔记和两支笔，不购买新收纳盒。先清点，再按用途摆放，最后记录缺少的东西。".repeat(2);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        mapper.insertIfAbsent(new Workflow("synthetic", "member", 1, "合成验收", hash,
                "PENDING", null, "PENDING", null, "PENDING", null, null, null, null));
        var docs = mock(DocMapper.class);
        var entity = new DocEntity(); entity.setId("synthetic"); entity.setAuthorId("member"); entity.setKbId(KnowledgeService.NOTES_KB_ID);
        when(docs.selectById("synthetic")).thenReturn(entity);
        var knowledge = mock(KnowledgeService.class);
        when(knowledge.getDoc("synthetic")).thenReturn(new DocDto("synthetic", "合成验收", "member", null, null, false,
                List.of(), List.of(), new RichDocContentDto(1, null, body), List.of(), KnowledgeService.NOTES_KB_ID, null, 1));
        var text = mock(TextPolishJobService.class);
        when(text.submit(any(), any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            jdbc.update("INSERT INTO queue_probe VALUES ('title-job')");
            return new TextPolishJobService.Result("title-job", "QUEUED", null, null, null, null);
        });
        var images = mock(ArticleImageService.class);
        when(images.startNoteWorkflow(any(), any())).thenThrow(new BadRequestException("隔离注入：配图提交被拒绝，请重试"));
        var service = new NoteAiWorkflowService(mapper, docs, knowledge, text, images,
                mock(NotePolishVersionService.class), mock(MemberPreferenceMapper.class), new ObjectMapper());
        try {
            var before = transaction.execute(status -> mapper.lock("synthetic"));
            Throwable error = catchThrowable(() -> transaction.executeWithoutResult(status -> service.advance("synthetic")));
            assertThat(error).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM queue_probe", Integer.class)).isZero();
            assertThat(transaction.<Workflow>execute(status -> mapper.lock("synthetic"))).isEqualTo(before);
            transaction.executeWithoutResult(status -> service.failAdvance("synthetic", (RuntimeException) error));
            var failed = transaction.execute(status -> mapper.lock("synthetic"));
            assertThat(failed.titleStatus()).isEqualTo("PENDING");
            assertThat(failed.titleJobId()).isNull();
            assertThat(failed.imageStatus()).isEqualTo("FAILED");
            assertThat(failed.polishStatus()).isEqualTo("PENDING");
            assertThat(failed.lastError()).contains("配图", "重试");
            doReturn(new TextPolishJobService.Result("resumed-text", "QUEUED", null, null, null, null))
                    .when(text).submit(any(), any(), any(), any(), any(), any(), any());
            transaction.executeWithoutResult(status -> service.advance("synthetic"));
            var progressed = transaction.execute(status -> mapper.lock("synthetic"));
            assertThat(progressed.titleStatus()).isEqualTo("RUNNING");
            assertThat(progressed.polishStatus()).isEqualTo("RUNNING");
            assertThat(progressed.imageStatus()).isEqualTo("FAILED");
            assertThat(progressed.lastError()).isEqualTo(failed.lastError());
            transaction.executeWithoutResult(status -> service.retry("member", "synthetic"));
            var retried = transaction.execute(status -> mapper.lock("synthetic"));
            assertThat(retried.imageStatus()).isEqualTo("PENDING");
            assertThat(retried.lastError()).isNull();
            transaction.executeWithoutResult(status -> service.failAdvance("synthetic", (RuntimeException) error));
            assertThat(transaction.<Workflow>execute(status -> mapper.lock("synthetic"))).isEqualTo(retried);
        } finally { jdbc.execute("SHUTDOWN"); }
    }
}
