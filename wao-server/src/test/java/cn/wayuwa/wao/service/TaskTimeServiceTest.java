package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.*;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.TaskDto;
import cn.wayuwa.wao.mapper.TaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskTimeServiceTest {
    JdbcTemplate jdbc;TransactionTemplate tx;TaskService tasks;TaskMapper mapper;ObjectMapper json;
    InternalNotificationService notifications;TaskChangeStream changes;TaskTimeService service;
    Map<String,TaskDto> records;
    @BeforeEach void setup(){
        var ds=new DriverManagerDataSource("jdbc:h2:mem:task-time-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(ds);jdbc.execute("CREATE TABLE task(id VARCHAR(32) PRIMARY KEY)");jdbc.update("INSERT INTO task(id) VALUES('t1')");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V197__task_labels.sql"),new ClassPathResource("db/migration/V207__task_time_rules.sql")).execute(ds);
        tx=new TransactionTemplate(new DataSourceTransactionManager(ds));json=new ObjectMapper();tasks=mock(TaskService.class);mapper=mock(TaskMapper.class);
        notifications=mock(InternalNotificationService.class);when(notifications.publishToMember(any(),any())).thenReturn(true);changes=mock(TaskChangeStream.class);records=new HashMap<>();records.put("t1",task("t1","me","2026-11-06"));
        when(tasks.get(anyString(),anyString())).thenAnswer(call->{TaskDto t=records.get(call.getArgument(0));if(t==null)throw new NotFoundException("不存在");return t;});
        when(tasks.lockWorkspaceTask(anyString(),anyString())).thenAnswer(call->tasks.get(call.getArgument(0),call.getArgument(1)));
        when(tasks.replace(anyString(),any(),anyString())).thenAnswer(call->{String id=call.getArgument(0);TaskDto t=call.getArgument(1);if(!records.containsKey(id))jdbc.update("INSERT INTO task(id) VALUES(?)",id);records.put(id,t);return t;});
        when(mapper.selectById(anyString())).thenAnswer(call->records.containsKey(call.getArgument(0))?new TaskEntity():null);
        service=new TaskTimeService(jdbc,tasks,mapper,json,notifications,changes,Clock.fixed(Instant.parse("2026-11-06T02:00:00Z"),ZoneOffset.UTC));
    }
    TaskDto task(String id,String owner,String start){
        ObjectNode n=json.createObjectNode();n.put("id",id);n.put("owner",owner);n.put("createdBy",owner);n.put("title","测试任务");n.put("start",start);n.put("due","2026-11-07");n.put("status","待办");n.put("project","");n.put("editVersion","v1");n.put("workItemType","task");n.put("pr","P2");n.put("desc","保留正文");
        for(String field:List.of("subs","steps","activity","files","images","comments","docs","parts","custom","linkTasks","pdcaCycles","hostedPages"))n.set(field,json.createArrayNode());
        return json.convertValue(n,TaskDto.class);
    }
    void change(String field,String value){ObjectNode n=json.valueToTree(records.get("t1"));n.put(field,value);records.put("t1",json.convertValue(n,TaskDto.class));}
    TaskTimeRule rule(){var r=new TaskTimeRule();r.repeat="daily";r.remindBefore=List.of(0);return r;}
    TaskTimeService.State save(TaskTimeRule r){return tx.execute(t->service.save("t1","me",r));}
    void process(){tx.executeWithoutResult(t->service.process("t1"));}
    @Test void onlyOwnerCanConfigureAndOthersCannotReadPrivateRule(){
        save(rule());assertThat(service.get("t1","other").editable()).isFalse();assertThat(service.get("t1","other").rule().remindBefore).isEmpty();
        assertThatThrownBy(()->tx.execute(t->service.save("t1","other",rule()))).isInstanceOf(BadRequestException.class);
    }
    @Test void saveRetryIsIdempotentAndConflictingDraftDoesNotOverwrite(){
        save(rule());assertThat(save(rule()).rule().revision).isEqualTo(1);
        var stale=rule();stale.interval=2;assertThatThrownBy(()->save(stale)).isInstanceOf(ConflictException.class);
        assertThat(service.get("t1","me").rule().interval).isEqualTo(1);
    }
    @Test void dateRequiredAndRelatedTasksCannotRepeat(){
        change("start","");assertThatThrownBy(()->save(rule())).isInstanceOf(BadRequestException.class);
        change("start","2026-11-06");when(mapper.countChildTasks("t1")).thenReturn(1);assertThatThrownBy(()->save(rule())).isInstanceOf(BadRequestException.class);
    }
    @Test void remindersArePrivateIdempotentAndRecomputeWhenDateMoves(){
        save(rule());process();process();verify(notifications,times(1)).publishToMember(eq("me"),any());
        change("start","2026-11-07");process();verify(notifications,times(1)).publishToMember(eq("me"),any());
        assertThat(service.get("t1","me").nextReminder()).isEqualTo("2026-11-07T09:00");
    }
    @Test void deliveryFailureRollsBackReceiptAndNextAttemptCanRetry(){
        save(rule());when(notifications.publishToMember(any(),any())).thenThrow(new IllegalStateException("offline")).thenReturn(true);
        assertThatThrownBy(this::process).isInstanceOf(IllegalStateException.class);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_time_delivery",Integer.class)).isZero();
        process();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_time_delivery",Integer.class)).isEqualTo(1);
    }
    @Test void changingOwnerSuspendsPreviousUsersRule(){
        save(rule());change("owner","other");process();verifyNoInteractions(notifications);assertThat(service.pendingIds()).isEmpty();
        assertThat(service.get("t1","other").rule().revision).isZero();
    }
    @Test void completedTaskCreatesExactlyOneNextOccurrenceAndRetainsHistoryLabelsAndContent(){
        jdbc.update("INSERT INTO task_labels(task_id,sub_id,revision,labels_json) VALUES('t1','',1,'[\"工作\"]')");save(rule());
        change("status","已完成");change("completedAt","2026-11-06T09:30:00+08:00");process();process();
        var state=service.get("t1","me");assertThat(state.nextTaskId()).isNotBlank();assertThat(records).hasSize(2);
        TaskDto next=records.get(state.nextTaskId());assertThat(next.start()).isEqualTo("2026-11-07");assertThat(next.due()).isEqualTo("2026-11-08");assertThat(next.status()).isEqualTo("待办");assertThat(next.desc()).isEqualTo("保留正文");
        assertThat(records.get("t1").status()).isEqualTo("已完成");assertThat(jdbc.queryForObject("SELECT labels_json FROM task_labels WHERE task_id=?",String.class,next.id())).isEqualTo("[\"工作\"]");
        verifyNoInteractions(notifications);
    }
    @Test void completionModeStartsFromActualCompletionAndInterval(){
        var r=rule();r.mode="completion";r.interval=3;save(r);change("start","2026-10-30");change("status","已完成");change("completedAt","2026-11-06T09:30:00+08:00");process();
        assertThat(records.get(service.get("t1","me").nextTaskId()).start()).isEqualTo("2026-11-09");
    }
    @Test void skipChecksTaskAndRuleVersionsAndResponseRetryDoesNotSkipAgain(){
        save(rule());assertThatThrownBy(()->tx.execute(t->service.skip("t1","me",new TaskTimeService.Advance(1,"old")))).isInstanceOf(ConflictException.class);
        var state=tx.execute(t->service.skip("t1","me",new TaskTimeService.Advance(1,"v1")));
        var retry=tx.execute(t->service.skip("t1","me",new TaskTimeService.Advance(1,"v1")));
        assertThat(retry.nextTaskId()).isEqualTo(state.nextTaskId());assertThat(records).hasSize(2);assertThat(records.get("t1").status()).isEqualTo("已放弃");
    }
    @Test void skippingFutureCompletionSeriesAdvancesBeyondTheSkippedOccurrence(){
        change("start","2026-12-01");var r=rule();r.mode="completion";r.interval=3;save(r);
        var state=tx.execute(t->service.skip("t1","me",new TaskTimeService.Advance(1,"v1")));
        assertThat(records.get(state.nextTaskId()).start()).isEqualTo("2026-12-04");
    }
    @Test void malformedNullRuleFieldsReturnValidationErrors(){
        var missingRepeat=rule();missingRepeat.repeat=null;assertThatThrownBy(()->save(missingRepeat)).isInstanceOf(BadRequestException.class);
        var missingMode=rule();missingMode.mode=null;assertThatThrownBy(()->save(missingMode)).isInstanceOf(BadRequestException.class);
        var missingAnchor=rule();missingAnchor.anchor=null;assertThatThrownBy(()->save(missingAnchor)).isInstanceOf(BadRequestException.class);
    }
    @Test void endingSeriesDoesNotGenerateAnotherTask(){
        var r=rule();r.until="2026-11-06";save(r);change("status","已完成");process();assertThat(records).hasSize(1);assertThat(service.pendingIds()).isEmpty();
    }
    @Test void explicitTestUsesReceiptAndDoesNotDeliverTwiceOnRetry(){
        save(rule());String operation=UUID.randomUUID().toString();tx.execute(t->service.test("t1","me",operation));tx.execute(t->service.test("t1","me",operation));verify(notifications,times(1)).publishToMember(eq("me"),any());
    }
    @Test void incompleteSubtasksDelayRecurrenceAndCompletedSubtreeIsCopiedWithFreshIdentity(){
        ObjectNode original=json.valueToTree(records.get("t1"));
        ObjectNode sub=original.withArray("subs").addObject();sub.put("id","child");sub.put("text","子任务");sub.put("done",false);sub.put("due","2026-11-07");sub.withArray("comments").addObject().put("text","旧评论");
        sub.withArray("images").addObject().put("url","/uploads/test.png").put("name","图片");
        records.put("t1",json.convertValue(original,TaskDto.class));save(rule());change("status","已完成");process();
        assertThat(records).hasSize(1);assertThat(service.get("t1","me").error()).contains("未完成子任务");
        original=json.valueToTree(records.get("t1"));((ObjectNode)original.withArray("subs").get(0)).put("done",true);records.put("t1",json.convertValue(original,TaskDto.class));
        jdbc.update("INSERT INTO task_labels(task_id,sub_id,revision,labels_json) VALUES('t1','child',1,'[\"子标签\"]')");
        process();TaskDto next=records.get(service.get("t1","me").nextTaskId());
        assertThat(next.subs()).hasSize(1);var copy=next.subs().get(0);assertThat(copy.id()).isNotEqualTo("child");assertThat(copy.done()).isFalse();assertThat(copy.comments()).isEmpty();assertThat(copy.images()).hasSize(1);assertThat(copy.due()).isEqualTo("2026-11-08");
        assertThat(jdbc.queryForObject("SELECT labels_json FROM task_labels WHERE task_id=? AND sub_id=?",String.class,next.id(),copy.id())).isEqualTo("[\"子标签\"]");
    }
    @Test void removingReminderAndRecurrenceStopsBothWithoutDeletingHistory(){
        var saved=save(rule());var r=saved.rule();r.repeat="none";r.remindBefore=List.of();save(r);process();verifyNoInteractions(notifications);
        change("status","已完成");process();assertThat(records).hasSize(1);assertThat(service.pendingIds()).isEmpty();
    }
    @Test void manualDateChangeUsesNewMonthlyAnchor(){
        change("start","2026-10-31");var r=rule();r.repeat="monthly";save(r);change("start","2026-11-15");change("status","已完成");process();
        assertThat(records.get(service.get("t1","me").nextTaskId()).start()).isEqualTo("2026-12-15");
    }
    @Test void newOwnerMustOptInAndHasIndependentReminderReceipt(){
        save(rule());process();change("owner","other");process();
        assertThat(service.get("t1","other").lastDeliveredAt()).isNull();
        tx.execute(t->service.save("t1","other",rule()));process();
        verify(notifications,times(1)).publishToMember(eq("me"),any());verify(notifications,times(1)).publishToMember(eq("other"),any());
    }
    @Test void previewUsesManuallyChangedAnchorAndNotesCannotSkip(){
        change("start","2026-10-31");var r=rule();r.repeat="monthly";save(r);change("start","2026-11-15");
        assertThat(service.get("t1","me").nextDate()).isEqualTo("2026-12-15");
        change("workItemType","note");assertThatThrownBy(()->tx.execute(t->service.skip("t1","me",new TaskTimeService.Advance(1,"v1")))).isInstanceOf(BadRequestException.class);
        assertThat(records).hasSize(1);
    }
    @Test void disabledSchedulerIsVisibleAndCannotPromiseAutomaticReminders(){
        org.springframework.test.util.ReflectionTestUtils.setField(service,"globalScheduling",false);
        assertThat(service.get("t1","me").schedulerEnabled()).isFalse();
        assertThatThrownBy(()->save(rule())).isInstanceOf(BadRequestException.class).hasMessageContaining("后台提醒");
        var r=rule();r.remindBefore=List.of();assertThat(save(r).rule().repeat).isEqualTo("daily");
    }
    @org.springframework.context.annotation.Configuration
    @org.springframework.transaction.annotation.EnableTransactionManagement
    static class Transactions {}
    @Test void completionListenerRunsInNewTransactionOnlyAfterCommit(){
        save(rule());change("status","已完成");
        try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()){
            context.register(Transactions.class);
            context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->tx.getTransactionManager());
            context.registerBean(TaskTimeService.class,()->service);
            context.registerBean(TaskTimeScheduler.class,()->new TaskTimeScheduler(context.getBean(TaskTimeService.class)));
            context.refresh();
            tx.executeWithoutResult(t->{context.publishEvent(new TaskCompletedEvent("t1"));assertThat(records).hasSize(1);t.setRollbackOnly();});
            assertThat(records).hasSize(1);
            tx.executeWithoutResult(t->{context.publishEvent(new TaskCompletedEvent("t1"));assertThat(records).hasSize(1);});
            assertThat(records).hasSize(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_time_rule",Integer.class)).isEqualTo(2);
        }
    }
}
