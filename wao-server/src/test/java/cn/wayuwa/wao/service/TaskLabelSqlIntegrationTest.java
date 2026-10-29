package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.dto.Step;
import cn.wayuwa.wao.dto.TaskDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises actual V197 DDL and service SQL in an isolated H2 MySQL-mode database. */
class TaskLabelSqlIntegrationTest {
    JdbcTemplate jdbc;
    TransactionTemplate transaction;
    TaskLabelService service;
    TaskService tasks;
    TaskDto source;
    TaskDto target;

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:task-labels-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE task(id VARCHAR(32) PRIMARY KEY)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V197__task_labels.sql")).execute(ds);
        jdbc.update("INSERT INTO task(id) VALUES('source'),('target')");
        transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
        tasks = mock(TaskService.class);
        source = mock(TaskDto.class); target = mock(TaskDto.class);
        when(source.id()).thenReturn("source"); when(target.id()).thenReturn("target");
        when(source.subs()).thenReturn(List.of(new Step("root child",false,"s1",null,List.of()), new Step("descendant",false,"s2",null,List.of())));
        when(target.subs()).thenReturn(List.of(new Step("moved descendant",false,"s2",null,List.of())));
        when(tasks.list("member")).thenReturn(List.of(source,target));
        when(tasks.lockWorkspaceTask("source","member")).thenAnswer(call -> {
            jdbc.queryForObject("SELECT id FROM task WHERE id='source' FOR UPDATE",String.class); return source;
        });
        when(tasks.lockWorkspaceTask("target","member")).thenAnswer(call -> {
            jdbc.queryForObject("SELECT id FROM task WHERE id='target' FOR UPDATE",String.class); return target;
        });
        service = new TaskLabelService(jdbc,tasks,new ObjectMapper());
    }
    @AfterEach void close() { if(jdbc!=null) jdbc.execute("SHUTDOWN"); }
    TaskLabelService.Labels save(String task,String sub,long revision,String... labels) {
        return transaction.execute(status -> service.save(task,new TaskLabelService.Change(sub,revision,List.of(labels)),"member"));
    }
    TaskLabelService.Labels find(String task,String sub) {
        return service.list("member").stream().filter(row->row.taskId().equals(task)&&row.subId().equals(sub)).findFirst().orElseThrow();
    }
    @Test void actualSqlPersistsRejectsStaleRevisionRetriesIdempotentlyAndClears() {
        var first=save("source","",0,"联调"," 已验证 ","联调");
        assertThat(first.revision()).isEqualTo(1);
        assertThat(find("source","").tags()).containsExactly("联调","已验证");
        var second=save("source","",1,"新的标签");
        assertThat(second.revision()).isEqualTo(2);
        assertThatThrownBy(()->save("source","",1,"旧标签")).isInstanceOf(ConflictException.class);
        assertThat(find("source","").tags()).containsExactly("新的标签");
        assertThat(save("source","",1,"新的标签").revision()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_labels",Integer.class)).isEqualTo(1);
        assertThat(save("source","",2).revision()).isEqualTo(3);
        assertThat(find("source","").tags()).isEmpty();
    }
    @Test void actualMoveSqlRetainsRootAndDescendantLabelsAndCascadesWithTaskDeletion() {
        save("source","",0,"parent"); save("source","s1",0,"promoted"); save("source","s2",0,"descendant");
        transaction.executeWithoutResult(status -> {
            service.move("source","s1","target","");
            service.move("source","s2","target","s2");
        });
        assertThat(find("source","").tags()).containsExactly("parent");
        assertThat(find("target","").tags()).containsExactly("promoted");
        assertThat(find("target","s2").tags()).containsExactly("descendant");
        assertThat(find("target","").revision()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_labels WHERE task_id='source' AND sub_id<>''",Integer.class)).isZero();
        jdbc.update("DELETE FROM task WHERE id='target'");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_labels WHERE task_id='target'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_labels",Integer.class)).isEqualTo(1);
    }
    @Test void moveParticipatesInTransactionRollback() {
        save("source","s1",0,"keep");
        transaction.executeWithoutResult(status -> { service.move("source","s1","target",""); status.setRollbackOnly(); });
        assertThat(find("source","s1").tags()).containsExactly("keep");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_labels WHERE task_id='target'",Integer.class)).isZero();
    }
}
