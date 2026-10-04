package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实状态表与调度批次验证队列继续推进，模型与文档出口隔离，不连接共享库。 */
class NoteAiWorkflowQueueTest {
    @ParameterizedTest @ValueSource(ints = {1, 3})
    void waitingNotesReleaseCompletedJobsAndRespectSharedMemberCapacity(int capacity) throws Exception {
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
        jdbc.execute("""
                CREATE TABLE note_ai_stage_attempt (
                  doc_id VARCHAR(64), state VARCHAR(16))
                """);
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(NoteAiWorkflowMapper.class);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(ds); factory.setConfiguration(configuration);
        var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(NoteAiWorkflowMapper.class);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
        String body = "合成排队验收：三项已经完成，其他三十篇正在等待释放入队名额。";
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        for (int i = 0; i < 30 + capacity; i++) {
            boolean running = i >= 30;
            mapper.insertIfAbsent(new Workflow("note" + i, "member", 1, "合成标题", hash,
                    running ? "RUNNING" : "PENDING", running ? "job" + i : null,
                    "SKIPPED", null, "SKIPPED", null, null, null, null));
            jdbc.update("UPDATE note_ai_workflow SET updated_at=? WHERE doc_id=?",
                    java.sql.Timestamp.valueOf(running ? "2026-01-02 00:00:00" : "2026-01-01 00:00:00"), "note" + i);
            // 创建入口只写初始选择，运行中的任务 ID 由后续提交保存。
            if (running) jdbc.update("UPDATE note_ai_workflow SET title_job_id=? WHERE doc_id=?", "job" + i, "note" + i);
        }
        var docs = mock(DocMapper.class);
        when(docs.selectById(any())).thenAnswer(call -> {
            var doc = new DocEntity(); doc.setId(call.getArgument(0)); doc.setAuthorId("member");
            doc.setKbId(KnowledgeService.NOTES_KB_ID); return doc;
        });
        var knowledge = mock(KnowledgeService.class);
        when(knowledge.getDoc(any())).thenAnswer(call -> note(call.getArgument(0), "合成标题", body, 1));
        when(knowledge.updateDerivedNote(any(), any())).thenAnswer(call -> note(call.getArgument(0), "合成已完成", body, 2));
        var text = mock(TextPolishJobService.class);
        when(text.get(eq("member"), any())).thenAnswer(call -> new TextPolishJobService.Result(
                call.getArgument(1), "SUCCEEDED", null, "合成已完成", null, null));
        when(text.submit(any(), any(), any(), any(), any(), any(), any())).thenAnswer(call ->
                new TextPolishJobService.Result(UUID.randomUUID().toString(), "QUEUED", null, null, null, null));
        var preferences = mock(MemberPreferenceMapper.class);
        if (capacity == 1) when(preferences.find("member", "note-ai-defaults-v1"))
                .thenReturn("{\"executionMode\":\"serial\",\"maxConcurrent\":3}");
        var service = new NoteAiWorkflowService(mapper, docs, knowledge, text, mock(ArticleImageService.class),
                mock(NotePolishVersionService.class), preferences, new ObjectMapper());
        try {
            assertThat(mapper.countRunningStages("member")).isEqualTo(capacity);
            var batch = mapper.pendingDocIds(30);
            for (String id : batch) transaction.executeWithoutResult(status -> service.advance(id));
            for (int i = 30; i < 30 + capacity; i++) {
                String id = "note" + i;
                assertThat(transaction.execute(status -> mapper.lock(id)).titleStatus()).as(id).isEqualTo("DONE");
            }
            // 已完成项释放槽位，多个小记的等待者共用成员上限。
            verify(text, times(capacity)).get(eq("member"), any());
            verify(text, times(capacity)).submit(any(), any(), any(), any(), any(), any(), any());
            assertThat(mapper.countRunningStages("member")).isEqualTo(capacity);
        } finally { jdbc.execute("SHUTDOWN"); }
    }

    private static DocDto note(String id, String title, String body, int rev) {
        return new DocDto(id, title, "member", null, null, false, List.of(), List.of(),
                new RichDocContentDto(1, null, body), List.of(), KnowledgeService.NOTES_KB_ID, null, rev);
    }
}
