package cn.wayuwa.wao.service;

import java.util.List;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.TaskDto;
import cn.wayuwa.wao.dto.TaskTemplateCreateRequest;
import cn.wayuwa.wao.dto.TaskTemplateRequest;
import cn.wayuwa.wao.dto.TaskTemplateRequest.Step;
import cn.wayuwa.wao.mapper.TaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskTemplateCreationServiceTest {
    private static final String TEMPLATE = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String TASK = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private JdbcTemplate db;
    private TaskTemplateService templates;
    private TaskTemplateCreationService service;
    private TaskService tasks;
    private TaskMapper mapper;
    private TransactionTemplate transaction;
    private final TaskTemplateCreateRequest request = new TaskTemplateCreateRequest("a1", TEMPLATE, "p1");

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        db = new JdbcTemplate(source);
        db.execute("CREATE TABLE task_template (id VARCHAR(32) PRIMARY KEY, member_id VARCHAR(64) NOT NULL, content_json CLOB NOT NULL, deleted TINYINT DEFAULT 0 NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL)");
        db.execute("CREATE TABLE task_template_creation (task_id VARCHAR(32) PRIMARY KEY, account_id VARCHAR(64) NOT NULL, member_id VARCHAR(64) NOT NULL, template_id VARCHAR(32) NOT NULL, project_id VARCHAR(64) NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL)");
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        templates = new TaskTemplateService(db, new ObjectMapper());
        templates.save(TEMPLATE, "m1", new TaskTemplateRequest("模板", "标题", "说明", List.of(new Step("a", null, "一", ""), new Step("b", "a", "二", "子说明"))));
        tasks = mock(TaskService.class); mapper = mock(TaskMapper.class);
        service = new TaskTemplateCreationService(db, templates, tasks, mapper);
        when(tasks.replace(eq(TASK), any(TaskDto.class), eq("m1"))).thenAnswer(call -> call.getArgument(1));
    }

    @Test void retryReturnsCurrentTaskWithoutOverwritingLaterEdits() {
        TaskDto created = transaction.execute(status -> service.create(TASK, "m1", request));
        assertNotNull(created);
        assertEquals(2, created.subs().size());
        assertEquals(created.subs().get(0).id(), created.subs().get(1).parentId());
        assertEquals("m1", created.subs().get(0).owner());
        assertFalse(created.subs().get(0).done());
        assertEquals("", created.subs().get(0).due());
        TaskDto current = new TaskDto(TASK, "后来修改", "p1", "待办", "P2", "m1", List.of(), "", "", null, "", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "m1");
        when(tasks.get(TASK, "m1")).thenReturn(current);
        templates.delete(TEMPLATE, "m1");
        assertEquals("后来修改", transaction.execute(status -> service.create(TASK, "m1", request)).title());
        verify(tasks, times(1)).replace(eq(TASK), any(TaskDto.class), eq("m1"));
        assertThrows(ConflictException.class, () -> transaction.execute(status -> service.create(TASK, "m1", new TaskTemplateCreateRequest("a1", TEMPLATE, "p2"))));
        assertThrows(NotFoundException.class, () -> transaction.execute(status -> service.create(TASK, "m1", new TaskTemplateCreateRequest("a2", TEMPLATE, "p1"))));
        when(tasks.get(TASK, "m1")).thenThrow(new NotFoundException("任务已删除"));
        assertThrows(NotFoundException.class, () -> transaction.execute(status -> service.create(TASK, "m1", request)));
        verify(tasks, times(1)).replace(eq(TASK), any(TaskDto.class), eq("m1"));
    }

    @Test void collisionOrMissingTemplateRollsBackTheReceipt() {
        when(mapper.lockById(TASK)).thenReturn(new TaskEntity());
        assertThrows(ConflictException.class, () -> transaction.execute(status -> service.create(TASK, "m1", request)));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM task_template_creation", Integer.class));
        when(mapper.lockById(TASK)).thenReturn(null);
        templates.delete(TEMPLATE, "m1");
        assertThrows(NotFoundException.class, () -> transaction.execute(status -> service.create(TASK, "m1", request)));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM task_template_creation", Integer.class));
        verify(tasks, never()).replace(anyString(), any(TaskDto.class), anyString());
    }
}
