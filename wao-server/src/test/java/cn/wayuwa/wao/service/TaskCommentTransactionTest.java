package cn.wayuwa.wao.service;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.mapper.TaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskCommentTransactionTest {
    @Test
    void concurrentCommentsSerializeAndNotificationsCommitWithTheirComments() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:comment_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        var db = new JdbcTemplate(source);
        db.execute("CREATE TABLE task (id VARCHAR(32) PRIMARY KEY, detail_json CLOB NOT NULL)");
        db.execute("CREATE TABLE notice (message VARCHAR(255) NOT NULL)");
        db.update("INSERT INTO task VALUES (?, ?)", "t1", "{\"comments\":[],\"activity\":[]}");
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        var mapper = mock(TaskMapper.class);
        when(mapper.lockById("t1")).thenAnswer(call -> db.queryForObject(
                "SELECT detail_json FROM task WHERE id=? FOR UPDATE", (row, index) -> {
                    var task = new TaskEntity();
                    task.setId("t1"); task.setProjectId(""); task.setOwnerId("wg");
                    task.setTitle("测试任务"); task.setStatus("待办"); task.setPr("P2");
                    task.setDescription(""); task.setDetailJson(row.getString(1));
                    return task;
                }, "t1"));
        when(mapper.updateDetail(anyString(), anyString())).thenAnswer(call ->
                db.update("UPDATE task SET detail_json=? WHERE id=?", (String) call.getArgument(1), (String) call.getArgument(0)));
        var notifier = mock(FeishuCommentNotifier.class);
        doAnswer(call -> { db.update("INSERT INTO notice VALUES (?)", ((cn.wayuwa.wao.dto.CommentDto) call.getArgument(1)).text()); return null; })
                .when(notifier).notifyComment(any(), any(), anyString(), anyString(), anyList());
        var members = mock(MemberService.class);
        when(members.list()).thenReturn(List.of());
        var projects = mock(ProjectService.class);
        when(projects.get("")).thenThrow(new NotFoundException("无项目"));
        var numbers = mock(WorkItemNumberService.class);
        when(numbers.attach(anyList())).thenAnswer(call -> call.getArgument(0));
        var service = new TaskService(mapper, new ObjectMapper(), notifier, mock(FeishuTaskNotifier.class), members, projects, numbers);

        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> { start.await(); return transactions.execute(status -> service.addComment("t1", "wg", "第一条")); });
            var second = executor.submit(() -> { start.await(); return transactions.execute(status -> service.addComment("t1", "wg", "第二条")); });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).id()).isNotEqualTo(second.get(10, TimeUnit.SECONDS).id());
        } finally {
            executor.shutdownNow();
        }
        var comments = new ObjectMapper().readTree(db.queryForObject("SELECT detail_json FROM task WHERE id='t1'", String.class)).path("comments");
        assertThat(comments.size()).isEqualTo(2);
        assertThat(List.of(comments.get(0).path("text").asText(), comments.get(1).path("text").asText()))
                .containsExactlyInAnyOrder("第一条", "第二条");
        assertThat(db.queryForList("SELECT message FROM notice", String.class)).containsExactlyInAnyOrder("第一条", "第二条");

        transactions.executeWithoutResult(status -> { service.addComment("t1", "wg", "回滚的评论"); status.setRollbackOnly(); });
        assertThat(new ObjectMapper().readTree(db.queryForObject("SELECT detail_json FROM task WHERE id='t1'", String.class)).path("comments").size()).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM notice", Integer.class)).isEqualTo(2);
    }
}
