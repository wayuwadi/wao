package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.*;
import cn.wayuwa.wao.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FocusServiceTest {
    JdbcTemplate jdbc; TransactionTemplate tx; FocusService service; TaskService tasks; KnowledgeService notes;
    long now; Clock clock;
    final String id="0123456789abcdef0123456789abcdef";
    @BeforeEach void setup(){
        var ds=new DriverManagerDataSource("jdbc:h2:mem:focus-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(ds);jdbc.execute("CREATE TABLE member(id VARCHAR(64) PRIMARY KEY)");jdbc.update("INSERT INTO member(id) VALUES('me'),('other')");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V198__focus_sessions.sql")).execute(ds);
        tx=new TransactionTemplate(new DataSourceTransactionManager(ds));tasks=mock(TaskService.class);notes=mock(KnowledgeService.class);
        now=Instant.parse("2026-10-29T02:00:00Z").toEpochMilli();clock=mock(Clock.class);
        when(clock.millis()).thenAnswer(inv->now);when(clock.withZone(any())).thenAnswer(inv->Clock.fixed(Instant.ofEpochMilli(now),inv.getArgument(0)));
        service=new FocusService(jdbc,new ObjectMapper(),tasks,notes,clock);
    }
    FocusService.Session start(String mode,int minutes,String task,String sub){return tx.execute(t->service.start(id,new FocusService.Start(mode,minutes,task,sub,"开始前的笔记"),"me"));}
    FocusService.Session change(FocusService.Session s,String action,String note){return tx.execute(t->service.change(id,new FocusService.Change(s.revision,action,note),"me"));}
    @Test void pauseResumeExcludePausedTimeAndFinishCreatesExactlyOneTaggedNote(){
        var s=start("pomodoro",25,"","");now+=60_000;s=change(s,"pause","阶段想法");assertThat(s.elapsedMs).isEqualTo(60_000);
        now+=300_000;s=change(s,"resume","阶段想法");now+=120_000;s=change(s,"finish","完成笔记");
        assertThat(s.elapsedMs).isEqualTo(180_000);assertThat(s.status).isEqualTo("finished");
        var capture=org.mockito.ArgumentCaptor.forClass(CreateNoteRequest.class);
        verify(notes).createNote(eq(s.noteId),capture.capture(),eq("me"));
        assertThat(capture.getValue().tags()).containsExactly("番茄钟");assertThat(capture.getValue().blocks().get(1).p()).isEqualTo("完成笔记");
        assertThat(change(s,"finish","重复请求").noteId).isEqualTo(s.noteId);verifyNoMoreInteractions(notes);
        assertThat(service.state("me").stats().totalMs()).isEqualTo(180_000);assertThat(service.state("me").active()).isNull();
    }
    @Test void overdueCountdownUsesDeadlineAndCapsElapsedWhileStopwatchIsUnbounded(){
        var s=start("pomodoro",1,"","");long beginning=now;now+=600_000;s=change(s,"finish","");
        assertThat(s.elapsedMs).isEqualTo(60_000);assertThat(s.endedAt).isEqualTo(beginning+60_000);
        var second=tx.execute(t->service.start("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",new FocusService.Start("stopwatch",25,"","",""),"me"));
        now+=1_000_000;assertThat(FocusService.elapsed(second,now)).isEqualTo(1_000_000);
    }
    @Test void sameStartIsIdempotentAndOnlyOneActiveSessionPerMember(){
        var first=start("pomodoro",25,"","");assertThat(start("pomodoro",25,"","").startedAt).isEqualTo(first.startedAt);
        assertThatThrownBy(()->tx.execute(t->service.start("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",new FocusService.Start("pomodoro",25,"","",""),"me"))).isInstanceOf(ConflictException.class);
        assertThat(service.state("other").active()).isNull();assertThat(service.state("other").history()).isEmpty();
        assertThatThrownBy(()->tx.execute(t->service.change(id,new FocusService.Change(1,"finish",""),"other"))).isInstanceOf(NotFoundException.class);
    }
    @Test void staleRevisionCannotOverwriteNoteAndFailedNoteSaveRollsBackFinish(){
        var s=start("pomodoro",25,"","");var updated=change(s,"save","最新笔记");
        assertThatThrownBy(()->change(s,"save","过时覆盖")).isInstanceOf(ConflictException.class);
        when(notes.createNote(anyString(),any(),eq("me"))).thenThrow(new IllegalStateException("offline"));
        now+=30_000;assertThatThrownBy(()->change(updated,"finish","重试笔记")).isInstanceOf(IllegalStateException.class);
        assertThat(service.state("me").active().status).isEqualTo("running");assertThat(service.state("me").history()).isEmpty();
        assertThat(service.state("me").active().note).isEqualTo("最新笔记");
    }
    @Test void sharedTaskAndSubtaskRequireExistingVisibility(){
        TaskDto task=mock(TaskDto.class);when(task.id()).thenReturn("shared");when(task.title()).thenReturn("共享任务");when(task.subs()).thenReturn(List.of(new Step("共享子任务",false,"sub",null,List.of())));
        when(tasks.get("shared","me")).thenReturn(task);
        var s=start("pomodoro",25,"shared","sub");assertThat(s.taskTitle).isEqualTo("共享子任务");verify(tasks).get("shared","me");
        now+=10_000;change(s,"finish","说明");
        var capture=org.mockito.ArgumentCaptor.forClass(CreateNoteRequest.class);verify(notes).createNote(anyString(),capture.capture(),eq("me"));
        assertThat(capture.getValue().blocks().get(1).p()).contains("/s/t/shared/sub");
        when(tasks.get("private","me")).thenThrow(new NotFoundException("不可见"));
        assertThatThrownBy(()->tx.execute(t->service.start("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",new FocusService.Start("pomodoro",25,"private","",""),"me"))).isInstanceOf(NotFoundException.class);
    }
    @Test void restDoesNotCreateNoteOrIncreaseFocusStats(){var s=start("break",5,"","");now+=300_000;change(s,"finish","");verifyNoInteractions(notes);assertThat(service.state("me").stats().totalCount()).isZero();}
    @Test void habitFocusChecksOwnershipAndSavesItsLinkWithoutCheckingIn(){
        RoutineService routines=mock(RoutineService.class);var habit=new RoutineService.Item();habit.kind="habit";habit.id="habit";habit.name="阅读习惯";
        when(routines.owned("me","habit")).thenReturn(habit);service=new FocusService(jdbc,new ObjectMapper(),tasks,notes,clock,routines);
        var s=tx.execute(t->service.start(id,new FocusService.Start("pomodoro",25,"","","读书笔记","habit"),"me"));
        assertThat(s.habitId).isEqualTo("habit");assertThat(s.taskTitle).isEqualTo("阅读习惯");now+=1000;change(s,"finish","读书笔记");
        var capture=org.mockito.ArgumentCaptor.forClass(CreateNoteRequest.class);verify(notes).createNote(anyString(),capture.capture(),eq("me"));
        assertThat(capture.getValue().blocks().get(1).p()).contains("/habits?habit=habit");verify(routines).owned("me","habit");verifyNoMoreInteractions(routines);
    }
}
