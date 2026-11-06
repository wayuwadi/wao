package cn.wayuwa.wao.service;

import java.util.List;
import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.dto.TaskTemplateRequest;
import cn.wayuwa.wao.dto.TaskTemplateRequest.Step;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.FileSystemResource;
import static org.junit.jupiter.api.Assertions.*;

class TaskTemplateServiceTest {
    private TaskTemplateService service;
    private static final String ID = "0123456789abcdef0123456789abcdef";

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var db = new JdbcTemplate(source);
        db.execute("CREATE TABLE task_template (id VARCHAR(32) PRIMARY KEY, member_id VARCHAR(64) NOT NULL, content_json CLOB NOT NULL, deleted TINYINT DEFAULT 0 NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL)");
        service = new TaskTemplateService(db, new ObjectMapper());
    }
    private TaskTemplateRequest request() {
        return new TaskTemplateRequest("模板", "标题", "正文", List.of(new Step("a", null, "子项", "说明"), new Step("b", "a", "下级", "")));
    }

    @Test void retriesAreIdempotentAndMembersAreIsolated() {
        assertEquals(request(), service.save(ID, "one", request()).content());
        assertEquals(request(), service.save(ID, "one", request()).content());
        assertEquals(1, service.list("one").size());
        assertTrue(service.list("two").isEmpty());
        assertThrows(NotFoundException.class, () -> service.save(ID, "two", request()));
        assertThrows(NotFoundException.class, () -> service.delete(ID, "two"));
        assertThrows(ConflictException.class, () -> service.save(ID, "one", new TaskTemplateRequest("另一个", "标题", "", List.of())));
        service.delete(ID, "one");
        service.delete(ID, "one");
        assertTrue(service.list("one").isEmpty());
        assertThrows(ConflictException.class, () -> service.save(ID, "one", request()));
    }

    @Test void rejectsInvalidAndCyclicSubtaskTrees() {
        assertThrows(BadRequestException.class, () -> service.save("invalid", "one", request()));
        assertThrows(BadRequestException.class, () -> service.save(ID, "one", new TaskTemplateRequest("模板", "标题", "", List.of(new Step("a", "missing", "子项", "")))));
        assertThrows(BadRequestException.class, () -> service.save(ID, "one", new TaskTemplateRequest("模板", "标题", "", List.of(new Step("a", "b", "一", ""), new Step("b", "a", "二", "")))));
        assertThrows(BadRequestException.class, () -> service.save(ID, "one", new TaskTemplateRequest("模板", "标题", "", List.of(new Step("a", null, "一", ""), new Step("a", null, "二", "")))));
    }

    @Test void migrationCreatesTheSchemaInMysqlCompatibilityMode() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new FileSystemResource("src/main/resources/db/migration/V206__task_templates.sql")).execute(source);
        var migrated = new TaskTemplateService(new JdbcTemplate(source), new ObjectMapper());
        migrated.save(ID, "one", request());
        assertEquals(request(), migrated.list("one").get(0).content());
        migrated.delete(ID, "one");
        assertTrue(migrated.list("one").isEmpty());
    }
}
