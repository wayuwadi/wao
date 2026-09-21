package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.dto.TaskDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 仅显式开启后访问现有 MySQL；只写随机验收回执，不伪装为真实任务操作验收。 */
@EnabledIfEnvironmentVariable(named = "WAO_TEST_TASK_WRITE_MYSQL", matches = "true")
class TaskWriteMysqlAcceptanceTest {
    @Test void mysqlSerializesEightClientsAndRollsBackFailedReceipts() throws Exception {
        var ds = new DriverManagerDataSource(System.getenv("WAO_TEST_NODE_JDBC"), System.getenv("DB_USER"), System.getenv("DB_PASS"));
        var jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        String account = "receipt-accept-" + UUID.randomUUID();
        String taskId = "receipt-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String operation = UUID.randomUUID().toString();
        String failedOperation = UUID.randomUUID().toString();
        var task = new TaskDto(taskId, "操作回执并发验收", "acceptance-no-business-write", "待办", "P2", "wg", List.of(), null, "", null, "",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()).withEditVersion("v1");
        TaskService tasks = mock(TaskService.class);
        when(tasks.get(taskId, "wg")).thenReturn(task);
        when(tasks.replace(taskId, task, "wg")).thenReturn(task);
        var service = new TaskWriteOperationService(jdbc, new ObjectMapper().findAndRegisterModules(), tasks);
        var pool = Executors.newFixedThreadPool(8);
        try {
            assertThat(jdbc.queryForObject("SELECT @@read_only + @@super_read_only", Integer.class)).isZero();
            var jobs = IntStream.range(0, 8).mapToObj(i -> (Callable<TaskDto>) () ->
                    tx.execute(status -> service.replace(account, "wg", taskId, operation, task))).toList();
            for (var result : pool.invokeAll(jobs)) assertThat(result.get()).isEqualTo(task);
            verify(tasks, times(1)).replace(taskId, task, "wg");
            assertThat(service.find(account, "wg", taskId, operation).status()).isEqualTo("COMMITTED");
            assertThat(service.find("other-" + UUID.randomUUID(), "wg", taskId, operation).status()).isEqualTo("UNCONFIRMED");
            assertThatThrownBy(() -> tx.execute(status -> service.replace(account, "wg", taskId, operation, task.withEditVersion("different"))))
                    .isInstanceOf(ConflictException.class);
            when(tasks.replace(taskId, task, "wg")).thenThrow(new IllegalStateException("simulated mutation failure"));
            assertThatThrownBy(() -> tx.execute(status -> service.replace(account, "wg", taskId, failedOperation, task)))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(service.find(account, "wg", taskId, failedOperation).status()).isEqualTo("UNCONFIRMED");
        } finally {
            pool.shutdownNow();
            jdbc.update("DELETE FROM wao_task_write_operation WHERE account_id = ? AND task_id = ?", account, taskId);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wao_task_write_operation WHERE account_id = ?", Integer.class, account)).isZero();
        }
    }
}
