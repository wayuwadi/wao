package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.wayuwa.wao.common.*;
import cn.wayuwa.wao.dto.*;
import cn.wayuwa.wao.mapper.TaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class TaskMenuServiceTest {
    TaskService tasks;TaskMapper mapper;TaskMenuService service;JdbcTemplate jdbc;TransactionTemplate tx;
    ObjectMapper json=new ObjectMapper().findAndRegisterModules();Map<String,TaskDto> rows;String operation;
    @BeforeEach void setup(){
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V158__task_write_operations.sql")).execute(ds);
        jdbc=new JdbcTemplate(ds);jdbc.execute("CREATE TABLE task(id VARCHAR(32) PRIMARY KEY)");jdbc.update("INSERT INTO task(id) VALUES('root')");jdbc.execute("CREATE TABLE task_labels(task_id VARCHAR(32),sub_id VARCHAR(100),revision BIGINT,labels_json VARCHAR(1000),PRIMARY KEY(task_id,sub_id),FOREIGN KEY(task_id) REFERENCES task(id))");
        tx=new TransactionTemplate(new DataSourceTransactionManager(ds));tasks=mock(TaskService.class);mapper=mock(TaskMapper.class);rows=new LinkedHashMap<>();
        var t=new TaskDto("root","任务","project","待办","P1","member",List.of(),null,"2026-11-01",null,"完整正文",List.of(),List.of(),List.of(),List.of("doc"),List.of(),List.of(),List.of(),List.of(),List.of()).withEditVersion("v1");rows.put(t.id(),t);
        when(tasks.get(anyString(),eq("member"))).thenAnswer(a->{var value=rows.get(a.<String>getArgument(0));if(value==null)throw new NotFoundException("missing");return value;});
        when(tasks.list("member")).thenAnswer(a->new ArrayList<>(rows.values()));
        when(tasks.replace(anyString(),any(),eq("member"))).thenAnswer(a->{TaskDto value=a.getArgument(1);value=value.withEditVersion("saved");jdbc.update("INSERT INTO task(id) VALUES(?) ON DUPLICATE KEY UPDATE id=id",value.id());rows.put(value.id(),value);return value;});
        doAnswer(a->{String id=a.getArgument(0);rows.put(id,rows.get(id).withParentTaskId(a.getArgument(1)));return 1;}).when(mapper).updateParent(anyString(),nullable(String.class));
        service=new TaskMenuService(tasks,mapper,jdbc,json);operation=UUID.randomUUID().toString();
    }
    TaskMenuService.Result apply(String action,String sub,Map<String,String> versions){return tx.execute(s->service.apply("account","member","root",operation,new TaskMenuService.Request(action,sub,versions)));}
    void subs(){ObjectNode n=json.valueToTree(rows.get("root"));n.set("subs",json.valueToTree(List.of(new Step("子任务",false,"s1","子内容",List.of(),1L,null,"member","2026-11-01"),new Step("后代",true,"s2","",List.of(),2L,"s1",null,null))));rows.put("root",json.convertValue(n,TaskDto.class));}
    @Test void duplicateRemapsTreeAndLabelsAndReplayDoesNotCreateAgain(){
        subs();rows.put("child",json.convertValue(((ObjectNode)json.valueToTree(rows.get("root"))).put("id","child").put("parentTaskId","root"),TaskDto.class));
        jdbc.update("INSERT INTO task_labels VALUES('root','s1',3,'[\"标签\"]')");
        var versions=Map.of("root","v1","child","v1");var result=apply("duplicate",null,versions);
        assertThat(result.tasks()).hasSize(2);var copy=result.tasks().get(0);
        assertThat(copy.id()).isNotEqualTo("root");assertThat(copy.number()).isNull();assertThat(copy.desc()).isEqualTo("完整正文");assertThat(copy.docs()).containsExactly("doc");
        assertThat(copy.subs().get(1).parentId()).isEqualTo(copy.subs().get(0).id());assertThat(copy.subs()).allMatch(s->!s.done() && s.number()==null);
        assertThat(result.tasks().get(1).parentTaskId()).isEqualTo(copy.id());
        assertThat(jdbc.queryForObject("SELECT labels_json FROM task_labels WHERE task_id=? AND sub_id=?",String.class,copy.id(),copy.subs().get(0).id())).isEqualTo("[\"标签\"]");
        assertThat(apply("duplicate",null,versions)).isEqualTo(result);verify(tasks,times(2)).replace(anyString(),any(),anyString());
    }
    @Test void subDuplicateCopiesOnlyBranchAndKeepsOriginalUnchanged(){subs();var result=apply("duplicate","s1",Map.of("root","v1"));var saved=result.tasks().get(0);assertThat(saved.subs()).hasSize(4);assertThat(saved.subs().get(1).done()).isTrue();assertThat(saved.subs().get(3).done()).isFalse();assertThat(result.selectedId()).startsWith("sub:root:");}
    @Test void abandonedBranchCanRestartWithoutReopeningCompletedChildren(){subs();var saved=apply("abandon",null,Map.of("root","v1")).tasks().get(0);assertThat(saved.status()).isEqualTo("已放弃");assertThat(saved.subs().get(0).status()).isEqualTo("已放弃");assertThat(saved.subs().get(1).done()).isTrue();operation=UUID.randomUUID().toString();var restored=apply("restore",null,Map.of("root","saved")).tasks().get(0);assertThat(restored.status()).isEqualTo("待办");assertThat(restored.subs().get(0).status()).isNull();assertThat(restored.subs().get(1).done()).isTrue();}
    @Test void conversionPreservesContentAndIdentityAndCanBeReversed(){var note=apply("note",null,Map.of("root","v1")).tasks().get(0);assertThat(note.id()).isEqualTo("root");assertThat(note.workItemType()).isEqualTo("note");assertThat(note.desc()).isEqualTo("完整正文");assertThat(note.docs()).containsExactly("doc");assertThat(note.due()).isEmpty();operation=UUID.randomUUID().toString();assertThat(apply("task",null,Map.of("root","saved")).tasks().get(0).workItemType()).isEqualTo("task");}
    @Test void preventsLossyConversionAndStaleEditsWithoutReceipt(){subs();assertThatThrownBy(()->apply("note",null,Map.of("root","v1"))).isInstanceOf(BadRequestException.class);assertThatThrownBy(()->apply("abandon",null,Map.of("root","stale"))).isInstanceOf(ConflictException.class);verify(tasks,never()).replace(anyString(),any(),anyString());assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wao_task_write_operation",Integer.class)).isZero();}
    @Test void replayChecksPermissionsAndPayload(){apply("note",null,Map.of("root","v1"));assertThatThrownBy(()->apply("task",null,Map.of("root","v1"))).isInstanceOf(ConflictException.class);when(tasks.get("root","member")).thenThrow(new ForbiddenException("revoked"));assertThatThrownBy(()->apply("note",null,Map.of("root","v1"))).isInstanceOf(ForbiddenException.class);}
    @Test void failedMutationRollsBackReceipt(){when(tasks.replace(anyString(),any(),anyString())).thenThrow(new IllegalStateException("failed"));assertThatThrownBy(()->apply("note",null,Map.of("root","v1"))).isInstanceOf(IllegalStateException.class);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wao_task_write_operation",Integer.class)).isZero();}
}
