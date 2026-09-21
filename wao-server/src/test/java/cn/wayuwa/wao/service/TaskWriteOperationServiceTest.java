package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.ForbiddenException;
import cn.wayuwa.wao.dto.TaskDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class TaskWriteOperationServiceTest {
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    TaskService tasks;
    TaskWriteOperationService service;
    TaskDto task;
    String operation;

    @BeforeEach
    void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V158__task_write_operations.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        tasks = mock(TaskService.class);
        service = new TaskWriteOperationService(jdbc, new ObjectMapper().findAndRegisterModules(), tasks);
        task = new TaskDto("receipt-task", "测试", "p1", "待办", "P2", "wg", List.of(), null, "", null, "正文",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()).withEditVersion("v1");
        operation = UUID.randomUUID().toString();
        when(tasks.get("receipt-task", "wg")).thenReturn(task);
        when(tasks.replace("receipt-task", task, "wg")).thenReturn(task);
    }

    TaskDto save(String account, String op, TaskDto payload) {
        return tx.execute(status -> service.replace(account, "wg", "receipt-task", op, payload));
    }

    @Test void repeatsReturnOriginalReceiptWithoutMutatingAgain() {
        assertThat(save("a", operation, task)).isEqualTo(task);
        assertThat(save("a", operation, task)).isEqualTo(task);
        assertThat(service.find("a", "wg", "receipt-task", operation).status()).isEqualTo("COMMITTED");
        verify(tasks, times(1)).replace("receipt-task", task, "wg");
    }

    @Test void differentPayloadForSameOperationIsRejected() {
        save("a", operation, task);
        assertThatThrownBy(() -> save("a", operation, task.withEditVersion("changed"))).isInstanceOf(ConflictException.class);
        verify(tasks, times(1)).replace(anyString(), any(), anyString());
    }

    @Test void receiptsAreAccountScopedAndPermissionsAreRechecked() {
        save("a", operation, task);
        assertThat(service.find("b", "wg", "receipt-task", operation).status()).isEqualTo("UNCONFIRMED");
        when(tasks.get("receipt-task", "wg")).thenThrow(new ForbiddenException("权限已撤销"));
        assertThatThrownBy(() -> save("a", operation, task)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.find("a", "wg", "receipt-task", operation)).isInstanceOf(ForbiddenException.class);
    }

    @Test void failedMutationRollsBackReceiptAndCanBeRetriedWithSameKey() {
        when(tasks.replace("receipt-task", task, "wg")).thenThrow(new IllegalStateException("failure")).thenReturn(task);
        assertThatThrownBy(() -> save("a", operation, task)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wao_task_write_operation", Integer.class)).isZero();
        assertThat(save("a", operation, task)).isEqualTo(task);
    }

    @Test void eightConcurrentRetriesHaveExactlyOneMutation() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<TaskDto>> calls = IntStream.range(0, 8).mapToObj(i -> pool.submit(() -> save("a", operation, task))).toList();
            for (Future<TaskDto> call : calls) assertThat(call.get()).isEqualTo(task);
            verify(tasks, times(1)).replace("receipt-task", task, "wg");
        } finally { pool.shutdownNow(); }
    }
}
