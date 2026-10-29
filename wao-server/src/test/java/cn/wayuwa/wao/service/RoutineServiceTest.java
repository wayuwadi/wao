package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.*;
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

class RoutineServiceTest {
    JdbcTemplate jdbc;TransactionTemplate tx;RoutineService service;InternalNotificationService notifications;
    final String id="0123456789abcdef0123456789abcdef";
    @BeforeEach void setup(){
        var ds=new DriverManagerDataSource("jdbc:h2:mem:routine-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(ds);jdbc.execute("CREATE TABLE member(id VARCHAR(64) PRIMARY KEY)");jdbc.update("INSERT INTO member(id) VALUES('me'),('other')");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V199__personal_routines.sql")).execute(ds);
        tx=new TransactionTemplate(new DataSourceTransactionManager(ds));notifications=mock(InternalNotificationService.class);
        service=new RoutineService(jdbc,new ObjectMapper(),notifications,Clock.fixed(Instant.parse("2026-10-29T02:00:00Z"),ZoneOffset.UTC));
    }
    RoutineService.Item item(String kind){var i=new RoutineService.Item();i.id=id;i.kind=kind;i.name="测试";i.date="2026-10-23";return i;}
    RoutineService.Item save(RoutineService.Item i){return tx.execute(s->service.save("me",id,i));}
    RoutineService.Log check(String date,long rev,int amount,String note){return tx.execute(s->service.checkin("me",id,date,new RoutineService.Checkin(rev,amount,note)));}
    @Test void memberIsolationAndOptimisticConflictProtectExistingRecord(){
        var original=item("countdown");save(original);
        assertThat(service.state("other",null).items()).isEmpty();
        assertThatThrownBy(()->service.owned("other",id)).isInstanceOf(NotFoundException.class);
        var a=service.owned("me",id);var b=service.owned("me",id);a.name="云端新内容";save(a);b.note="过时修改";
        assertThatThrownBy(()->save(b)).isInstanceOf(ConflictException.class);assertThat(service.owned("me",id).name).isEqualTo("云端新内容");
    }
    @Test void retryCreatesOneItemAndSoftDeleteCanRestoreWithoutLosingLogs(){
        save(item("habit"));assertThat(save(item("habit")).revision).isEqualTo(1);
        check("2026-10-29",0,1,"当天日志");var i=service.owned("me",id);i.status="trash";save(i);
        assertThatThrownBy(()->check("2026-10-29",1,0,"")).isInstanceOf(BadRequestException.class);
        i=service.owned("me",id);i.status="active";save(i);assertThat(service.state("me",null).logs()).hasSize(1);
    }
    @Test void checkinRetryUndoAndConflictKeepHistoryAndRejectFutureAndOtherMembers(){
        save(item("habit"));var log=check("2026-10-29",0,1,"第一条");assertThat(log.revision()).isEqualTo(1);
        assertThat(check("2026-10-29",0,1,"第一条").revision()).isEqualTo(1);
        assertThatThrownBy(()->check("2026-10-29",0,1,"不同草稿")).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->check("2026-10-30",0,1,"")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->tx.execute(t->service.checkin("other",id,"2026-10-29",new RoutineService.Checkin(0,1,"")))).isInstanceOf(NotFoundException.class);
        check("2026-10-29",1,0,"撤销后保留说明");assertThat(service.state("me",null).summaries().get(0).total()).isZero();
    }
    @Test void changedGoalDoesNotRewritePastCompletion(){
        var i=item("habit");i.target=2;save(i);check("2026-10-28",0,2,"完成两杯");
        i=service.owned("me",id);i.target=5;save(i);
        assertThat(service.state("me",null).summaries().get(0).total()).isEqualTo(1);
        assertThat(check("2026-10-28",1,2,"补充说明").target()).isEqualTo(2);
    }
    @Test void scheduledDaysStreakAndMonthCompletionIgnoreOffDays(){
        var i=item("habit");i.weekdays=List.of(1,3,5);save(i);
        check("2026-10-23",0,1,"");check("2026-10-26",0,1,"");check("2026-10-28",0,1,"");
        var stats=service.state("me",null).summaries().get(0);assertThat(stats.streak()).isEqualTo(3);assertThat(stats.monthRate()).isEqualTo(100);
        assertThatThrownBy(()->check("2026-10-27",0,1,"")).isInstanceOf(BadRequestException.class);
    }
    @Test void weeklyTargetCountsDaysNotRepeatedClicksAndIntervalHonorsEnd(){
        var i=item("habit");i.date="2026-10-19";i.frequency="weekly";i.weeklyTarget=2;save(i);
        check("2026-10-19",0,1,"");check("2026-10-20",0,1,"");check("2026-10-26",0,1,"");
        var stats=service.state("me",null).summaries().get(0);assertThat(stats.streak()).isEqualTo(1);assertThat(stats.streakUnit()).isEqualTo("周");assertThat(stats.weekCount()).isEqualTo(1);
        i.frequency="interval";i.intervalDays=3;i.durationDays=7;
        assertThat(RoutineCalendar.scheduled(i,LocalDate.parse("2026-10-25"))).isTrue();assertThat(RoutineCalendar.scheduled(i,LocalDate.parse("2026-10-28"))).isFalse();
    }
    @Test void annualMonthlyAndLunarDatesHandleYearBoundaries(){
        var i=item("countdown");i.date="2000-02-29";i.repeat="yearly";
        assertThat(RoutineCalendar.next(i,LocalDate.parse("2027-02-01"))).isEqualTo(LocalDate.parse("2027-02-28"));
        i.date="2026-01-31";i.repeat="monthly";assertThat(RoutineCalendar.next(i,LocalDate.parse("2026-02-01"))).isEqualTo(LocalDate.parse("2026-02-28"));
        assertThat(RoutineCalendar.next(i,LocalDate.parse("2026-03-01"))).isEqualTo(LocalDate.parse("2026-03-31"));
        i.date="2026-02-17";i.calendar="lunar";i.repeat="yearly";assertThat(RoutineCalendar.next(i,LocalDate.parse("2026-10-29"))).isEqualTo(LocalDate.parse("2027-02-06"));
        i.direction="up";assertThat(RoutineService.summary(i,List.of(),YearMonth.of(2026,10),LocalDate.parse("2026-02-18")).days()).isEqualTo(1);
    }
    @Test void remindersArePrivateIdempotentAndArchivedOrCompletedItemsDoNotNotify(){
        var i=item("countdown");i.date="2026-10-29";i.remindBefore=List.of(0,3);save(i);
        tx.executeWithoutResult(t->service.deliverDue(id));tx.executeWithoutResult(t->service.deliverDue(id));
        verify(notifications,times(1)).publishToMember(eq("me"),any());assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM personal_routine_delivery",Integer.class)).isEqualTo(1);
        i=service.owned("me",id);i.status="archived";save(i);assertThat(service.reminderIds()).isEmpty();
    }
    @Test void notificationFailureRollsBackDeliveryAndCanRetry(){
        var i=item("habit");i.reminderTimes=List.of("09:00");save(i);
        when(notifications.publishToMember(any(),any())).thenThrow(new IllegalStateException("offline")).thenReturn(true);
        assertThatThrownBy(()->tx.executeWithoutResult(t->service.deliverDue(id))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM personal_routine_delivery",Integer.class)).isZero();
        tx.executeWithoutResult(t->service.deliverDue(id));assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM personal_routine_delivery",Integer.class)).isEqualTo(1);
        check("2026-10-29",0,1,"");var current=service.owned("me",id);current.reminderTimes=List.of("09:00","09:30");save(current);
        tx.executeWithoutResult(t->service.deliverDue(id));verify(notifications,times(2)).publishToMember(eq("me"),any());
    }
}
