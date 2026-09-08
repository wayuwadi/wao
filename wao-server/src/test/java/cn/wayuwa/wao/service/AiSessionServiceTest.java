package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.AiTaskRunEntity;
import cn.wayuwa.wao.dto.AiSessionPreference;
import cn.wayuwa.wao.mapper.AiTaskRunMapper;
import cn.wayuwa.wao.mapper.TaskMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiSessionServiceTest {
    JdbcTemplate db;
    ProjectService projects=mock(ProjectService.class);
    AiTaskRunMapper runs=mock(AiTaskRunMapper.class);
    AiRunService ai=mock(AiRunService.class);
    AiSessionService service;
    @BeforeEach void setup() {
        db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+System.nanoTime()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        db.execute("CREATE TABLE ai_task_run(id VARCHAR PRIMARY KEY, task_id VARCHAR,project_id VARCHAR,runner_id VARCHAR,engine VARCHAR,requested_model VARCHAR,status VARCHAR,session_id VARCHAR,latest_progress VARCHAR,progress_updated_at TIMESTAMP,created_at TIMESTAMP)");
        db.execute("CREATE TABLE task(id VARCHAR PRIMARY KEY,title VARCHAR)");
        db.execute("CREATE TABLE project(id VARCHAR PRIMARY KEY,name VARCHAR)");
        db.execute("CREATE TABLE ai_runner_node(id VARCHAR PRIMARY KEY,name VARCHAR)");
        db.execute("CREATE TABLE ai_session_preference(member_id VARCHAR,run_id VARCHAR,title VARCHAR,pinned BOOLEAN,archived BOOLEAN,PRIMARY KEY(member_id,run_id))");
        db.update("INSERT INTO project VALUES ('p1','WAO'),('p2','Private')");
        db.update("INSERT INTO task VALUES ('t1','发布优化'),('t2','隐私任务')");
        db.update("INSERT INTO ai_runner_node VALUES ('n1','Linux 主节点')");
        when(projects.visibleProjectIds("u1")).thenReturn(Set.of("p1"));
        when(projects.visibleProjectIds("u2")).thenReturn(Set.of("p1"));
        service=new AiSessionService(db,projects,runs,mock(TaskMapper.class),ai);
    }
    void add(String id,String project,String status,String created,String updated) {
        db.update("INSERT INTO ai_task_run VALUES (?, 't1',?,'n1','codex','gpt-6-astra',?,?,?,CAST(? AS TIMESTAMP),CAST(? AS TIMESTAMP))",
                id,project,status,"thread-"+id,"最新进展",updated,created);
        var run=new AiTaskRunEntity();run.setId(id);run.setProjectId(project);when(runs.selectById(id)).thenReturn(run);
    }
    @Test void listFiltersVisibilityAndSortsResumedSessionsByLatestActivity() {
        add("old","p1","running","2026-01-01 00:00:00","2026-09-08 12:00:00");
        add("new","p1","succeeded","2026-09-07 00:00:00","2026-09-07 00:00:00");
        add("private","p2","running","2026-09-09 00:00:00","2026-09-09 00:00:00");
        assertThat(service.list("u1","","","",false,1).items()).extracting(x->x.id()).containsExactly("old","new");
        assertThat(service.list("u1","","active","codex",false,1).items()).extracting(x->x.id()).containsExactly("old");
        assertThat(service.list("u1","thread-new","","",false,1).items()).extracting(x->x.id()).containsExactly("new");
        assertThat(service.list("u1","%","","",false,1).total()).isZero();
        assertThat(service.list("u1","","","claude",false,1).items()).isEmpty();
    }
    @Test void preferencesArePersonalPersistentAndNeverStopOrDeleteTheRun() {
        add("a","p1","running","2026-09-08 00:00:00","2026-09-08 00:00:00");
        var saved=service.save("u1","a",new AiSessionPreference("  新名字  ",true,true));
        assertThat(saved.title()).isEqualTo("新名字");
        assertThat(service.list("u1","","","",false,1).items()).isEmpty();
        assertThat(service.list("u1","新名字","","",true,1).items()).singleElement().satisfies(x->{assertThat(x.pinned()).isTrue();assertThat(x.status()).isEqualTo("running");});
        assertThat(service.list("u2","","","",false,1).items()).singleElement().satisfies(x->{assertThat(x.title()).isEqualTo("发布优化");assertThat(x.pinned()).isFalse();});
        service.save("u1","a",new AiSessionPreference("",false,false));
        assertThat(service.list("u1","","","",false,1).items()).singleElement().satisfies(x->assertThat(x.title()).isEqualTo("发布优化"));
        verifyNoInteractions(ai);
    }
    @Test void paginatesEveryStatusAndPinTakesPriority() {
        for(int i=0;i<30;i++) add("r"+String.format("%02d",i),"p1",i%2==0?"failed":"cancelled","2026-09-08 00:00:00","2026-09-08 00:00:00");
        service.save("u1","r00",new AiSessionPreference(null,true,false));
        var first=service.list("u1","","","",false,1);var second=service.list("u1","","","",false,2);
        assertThat(first.total()).isEqualTo(30);assertThat(first.items()).hasSize(25);assertThat(first.items().get(0).id()).isEqualTo("r00");assertThat(second.items()).hasSize(5);
        assertThat(second.items()).extracting(x->x.id()).doesNotContainAnyElementsOf(first.items().stream().map(x->x.id()).toList());
    }
    @Test void unauthorizedDetailOrMutationNeverReadsConversationOrWritesPreferences() {
        add("secret","p2","succeeded","2026-09-08 00:00:00","2026-09-08 00:00:00");
        when(projects.get("p2","u1")).thenThrow(new NotFoundException("项目不存在"));
        assertThatThrownBy(()->service.detail("u1","secret")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(()->service.save("u1","secret",new AiSessionPreference(null,false,true))).isInstanceOf(NotFoundException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_session_preference",Integer.class)).isZero();verifyNoInteractions(ai);
    }
    @Test void rejectsInvalidFiltersAndBoundsSearch() {
        assertThatThrownBy(()->service.list("u1","","invented","",false,1)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->service.list("u1","x".repeat(161),"","",false,1)).isInstanceOf(BadRequestException.class);
        assertThat(service.list("nobody","","","",false,1).items()).isEmpty();
    }
}
