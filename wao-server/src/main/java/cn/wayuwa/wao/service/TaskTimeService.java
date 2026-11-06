package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.*;
import cn.wayuwa.wao.dto.TaskDto;
import cn.wayuwa.wao.feishu.FeishuWebhookNotifier.Card;
import cn.wayuwa.wao.mapper.TaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Rules, next-occurrence receipts and private notifications are independently revisioned. */
@Service
public class TaskTimeService {
    static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    public record State(TaskTimeRule rule, boolean editable, String reason, String nextTaskId,
                        String nextDate, String nextReminder, String lastDeliveredAt, String error, boolean schedulerEnabled) {}
    public record Advance(long revision, String editVersion) {}
    private record Stored(String member, TaskTimeRule rule, String next, String error) {}
    private final JdbcTemplate jdbc;
    private final TaskService tasks;
    private final TaskMapper mapper;
    private final ObjectMapper json;
    private final InternalNotificationService notifications;
    private final TaskChangeStream changes;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Value("${wao.scheduling.enabled:true}")
    private boolean globalScheduling=true;
    @org.springframework.beans.factory.annotation.Value("${wao.routines.scheduling-enabled:false}")
    private boolean routineScheduling;
    private boolean schedulerEnabled(){return globalScheduling || routineScheduling;}

    @Autowired public TaskTimeService(JdbcTemplate jdbc,TaskService tasks,TaskMapper mapper,ObjectMapper json,
            InternalNotificationService notifications,TaskChangeStream changes) {
        this(jdbc,tasks,mapper,json,notifications,changes,Clock.system(ZONE));
    }
    TaskTimeService(JdbcTemplate jdbc,TaskService tasks,TaskMapper mapper,ObjectMapper json,
            InternalNotificationService notifications,TaskChangeStream changes,Clock clock) {
        this.jdbc=jdbc;this.tasks=tasks;this.mapper=mapper;this.json=json;this.notifications=notifications;this.changes=changes;this.clock=clock;
    }
    private LocalDateTime now(){return LocalDateTime.now(clock.withZone(ZONE));}
    private boolean owner(TaskDto t,String member){return member!=null && member.equals(t.owner()==null||t.owner().isBlank()?t.createdBy():t.owner());}
    private void requireOwner(TaskDto t,String member){if(!owner(t,member))throw new BadRequestException("只有任务负责人可设置重复和个人提醒");}
    public State get(String id,String member) {
        TaskDto task=tasks.get(id,member);
        if(!owner(task,member))return new State(new TaskTimeRule(),false,"只有任务负责人可设置重复和个人提醒",null,null,null,null,"",schedulerEnabled());
        Stored row=stored(id);TaskTimeRule r=row==null?new TaskTimeRule():row.rule;
        boolean transferred=row!=null&&!row.member.equals(member);
        if(transferred)r=new TaskTimeRule();
        String next=row==null||transferred?null:row.next;
        String reason="note".equals(task.workItemType())?"笔记不设置重复和提醒":next!=null?"本次已结束，请在下一次任务上调整规则":Set.of("已完成","已放弃").contains(task.status())?"请先重新开始任务，再设置时间规则":"";
        LocalDate date=TaskTimeCalendar.anchor(task,r);
        if(date!=null&&!date.toString().equals(r.anchorDate))r.anchorDay=date.getDayOfMonth();
        LocalDate nextDate=date==null?null:TaskTimeCalendar.next(r,r.mode.equals("completion")?now().toLocalDate():date,now().toLocalDate());
        LocalDateTime reminder=TaskTimeCalendar.reminderAnchor(task,r),nextReminder=null;
        if(reminder!=null && reason.isEmpty())for(int before:r.remindBefore){LocalDateTime at=reminder.minusMinutes(before);if(!delivered(id,member,key(reminder,before))&&(nextReminder==null||at.isBefore(nextReminder)))nextReminder=at;}
        List<String> sent=jdbc.query("SELECT delivered_at FROM task_time_delivery WHERE task_id=? AND member_id=? ORDER BY delivered_at DESC LIMIT 1",(rs,n)->rs.getTimestamp(1).toLocalDateTime().toString(),id,member);
        return new State(r,reason.isEmpty(),reason,next,nextDate==null?null:nextDate.toString(),nextReminder==null?null:nextReminder.toString(),sent.isEmpty()?null:sent.get(0),row==null||transferred?"":Objects.toString(row.error,""),schedulerEnabled());
    }
    @Transactional public State save(String id,String member,TaskTimeRule request) {
        TaskDto task=tasks.lockWorkspaceTask(id,member);requireOwner(task,member);
        if("note".equals(task.workItemType())||Set.of("已完成","已放弃").contains(task.status()))throw new BadRequestException("仅未完成任务可设置重复和提醒");
        TaskTimeRule rule=TaskTimeCalendar.normalize(request),previous;
        if(!rule.remindBefore.isEmpty()&&!schedulerEnabled())throw new BadRequestException("此部署尚未启用后台提醒，请联系维护者；也可先保存不带提醒的重复规则");
        Stored row=stored(id);
        if(row!=null && row.next!=null)throw new ConflictException("本次已结束，请打开下一次任务");
        boolean sameOwner=row!=null && row.member.equals(member);
        previous=sameOwner?row.rule:new TaskTimeRule();
        LocalDate anchor=TaskTimeCalendar.anchor(task,rule);
        if((!rule.repeat.equals("none")||!rule.remindBefore.isEmpty())&&anchor==null)throw new BadRequestException(rule.anchor.equals("start")?"请先设置执行日期或时间":"请先设置截止日期");
        if(!rule.repeat.equals("none") && (task.parentTaskId()!=null || mapper.countChildTasks(id)>0))throw new BadRequestException("有关联父子任务时请先解除关系；内嵌子任务会随整条任务重复");
        if(!rule.until.isBlank() && anchor!=null && TaskTimeCalendar.date(rule.until).isBefore(anchor))throw new BadRequestException("结束日期不能早于本次日期");
        rule.anchorDay=anchor==null?0:previous.anchor.equals(rule.anchor)&&previous.repeat.equals(rule.repeat)&&anchor.toString().equals(previous.anchorDate)&&previous.anchorDay>0?previous.anchorDay:anchor.getDayOfMonth();
        rule.anchorDate=anchor==null?"":anchor.toString();
        long expected=rule.revision,current=sameOwner?row.rule.revision:0;
        rule.revision=current;
        if(expected!=current){if(expected+1==current && write(rule).equals(write(previous)))return get(id,member);throw new ConflictException("时间规则已在其他窗口更新，请保留草稿并重新读取比较");}
        rule.revision=current+1;
        jdbc.update("INSERT INTO task_time_rule(task_id,member_id,revision,body_json,active) VALUES(?,?,?,?,TRUE) ON DUPLICATE KEY UPDATE member_id=VALUES(member_id),revision=VALUES(revision),body_json=VALUES(body_json),active=TRUE,error_message=NULL",id,member,rule.revision,write(rule));
        return get(id,member);
    }
    /** Explicit skip is versioned and idempotent even when the response is lost. */
    @Transactional public State skip(String id,String member,Advance request) {
        TaskDto task=tasks.lockWorkspaceTask(id,member);requireOwner(task,member);
        if("note".equals(task.workItemType()))throw new BadRequestException("笔记不能跳过重复周期，请先转换为任务");
        Stored row=stored(id);if(row==null || !row.member.equals(member))throw new BadRequestException("请先保存重复规则");
        if(row.next!=null)return get(id,member);
        if(request==null || request.revision!=row.rule.revision || !Objects.equals(request.editVersion,task.editVersion()))throw new ConflictException("任务或时间规则已有更新，请重新读取后重试");
        if(row.rule.repeat.equals("none"))throw new BadRequestException("请先设置重复规则");
        if(Set.of("已完成","已放弃").contains(task.status()))throw new ConflictException("本次已结束，请刷新后查看");
        ObjectNode skipped=json.valueToTree(task);skipped.put("status","已放弃");
        TaskDto saved=tasks.replace(id,json.convertValue(skipped,TaskDto.class),member);
        LocalDate skipBase=now().toLocalDate(), occurrence=TaskTimeCalendar.anchor(saved,row.rule);
        if(occurrence!=null && occurrence.isAfter(skipBase))skipBase=occurrence;
        advance(saved,row,skipBase);
        changed(member);return get(id,member);
    }
    public List<String> pendingIds() {
        return jdbc.queryForList("SELECT task_id FROM task_time_rule WHERE active=TRUE ORDER BY updated_at,task_id",String.class);
    }
    /** Called through a proxy by the scheduler/event listener, so each task has its own transaction. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW) public void process(String id) {
        Stored initial=stored(id);if(initial==null)return;
        TaskDto task=tasks.lockWorkspaceTask(id,initial.member);
        Stored row=stored(id);if(row==null || row.next!=null)return;
        if(!owner(task,row.member)||"note".equals(task.workItemType())){suspend(id,"任务归属或类型已变化，提醒和重复已暂停");return;}
        if("已完成".equals(task.status())){
            if(task.subs()!=null && task.subs().stream().anyMatch(sub->!sub.done()&&!"已放弃".equals(sub.status())&&!"note".equals(sub.workItemType()))){jdbc.update("UPDATE task_time_rule SET error_message=? WHERE task_id=?","仍有未完成子任务，完成这些子任务后再生成下一次",id);return;}
            advance(task,row,completedDate(task));changed(row.member);return;
        }
        if("已放弃".equals(task.status()))return;
        LocalDateTime anchor=TaskTimeCalendar.reminderAnchor(task,row.rule);
        if(anchor==null && (!row.rule.remindBefore.isEmpty() || !row.rule.repeat.equals("none"))){jdbc.update("UPDATE task_time_rule SET error_message=? WHERE task_id=?","基准日期已清除，设置执行或截止日期后将恢复提醒",id);return;}
        if(anchor!=null)for(int before:row.rule.remindBefore){
            LocalDateTime at=anchor.minusMinutes(before);
            if(!at.isAfter(now()))deliver(id,row.member,key(anchor,before),task,"任务提醒 · "+task.title(),"计划时间："+anchor.toString().replace('T',' ')+"（上海时间）");
        }
        jdbc.update("UPDATE task_time_rule SET error_message=NULL WHERE task_id=?",id);
    }
    @Transactional public State retry(String id,String member) {
        TaskDto task=tasks.lockWorkspaceTask(id,member);requireOwner(task,member);process(id);return get(id,member);
    }
    @Transactional public State test(String id,String member,String operation) {
        TaskDto task=tasks.lockWorkspaceTask(id,member);requireOwner(task,member);
        if(operation==null||!operation.matches("[0-9a-f-]{36}"))throw new BadRequestException("试发编号不合法");
        Stored row=stored(id);if(row==null||!row.member.equals(member)||row.rule.remindBefore.isEmpty())throw new BadRequestException("请先保存至少一个提醒时间");
        deliver(id,member,"test:"+operation,task,"测试提醒 · "+task.title(),"这是你主动试发的站内提醒，仅本人可见。");
        return get(id,member);
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW) public void markFailure(String id){jdbc.update("UPDATE task_time_rule SET error_message=? WHERE task_id=?","时间规则暂未处理成功，将自动重试，也可手动重试",id);}
    private void suspend(String id,String reason){jdbc.update("UPDATE task_time_rule SET active=FALSE,error_message=? WHERE task_id=?",reason,id);}
    private void advance(TaskDto task,Stored row,LocalDate completed) {
        TaskTimeRule r=row.rule;
        if(r.repeat.equals("none")){jdbc.update("UPDATE task_time_rule SET active=FALSE WHERE task_id=?",task.id());return;}
        if(task.parentTaskId()!=null || mapper.countChildTasks(task.id())>0){suspend(task.id(),"父子任务关系已变化，重复已暂停，请解除关系后重新设置");return;}
        LocalDate base=TaskTimeCalendar.anchor(task,r);
        if(base==null){suspend(task.id(),"基准日期已清除，重复已暂停");return;}
        if(!base.toString().equals(r.anchorDate))r.anchorDay=base.getDayOfMonth();
        LocalDate next=TaskTimeCalendar.next(r,r.mode.equals("completion")?completed:base,now().toLocalDate());
        if(next==null){jdbc.update("UPDATE task_time_rule SET active=FALSE WHERE task_id=?",task.id());return;}
        long shift=ChronoUnit.DAYS.between(base,next);
        String newId=UUID.nameUUIDFromBytes(("task-repeat:"+task.id()).getBytes(StandardCharsets.UTF_8)).toString().replace("-","");
        ObjectNode copy=json.valueToTree(task);
        copy.put("id",newId);copy.put("status","待办");copy.put("createdBy",row.member);
        copy.remove(List.of("number","editVersion","subtaskVersion","createdAt","completedAt","parentTaskId","updatedAt"));
        for(String field:List.of("comments","activity","pdcaCycles","linkTasks"))copy.set(field,json.createArrayNode());
        shiftDate(copy,"start",shift);shiftDate(copy,"due",shift);shiftTime(copy,"startAt",shift);shiftTime(copy,"endAt",shift);
        for(var step:copy.withArray("steps")){((ObjectNode)step).put("done",false);((ObjectNode)step).putNull("status");}
        Map<String,String> subIds=new LinkedHashMap<>();
        var subs=TaskMenuService.steps(copy.withArray("subs"),task.id());
        for(String old:subs.keySet())subIds.put(old,UUID.nameUUIDFromBytes((newId+":"+old).getBytes(StandardCharsets.UTF_8)).toString().replace("-",""));
        subs.forEach((old,sub)->{sub.put("id",subIds.get(old));sub.remove("number");sub.put("done",false);sub.putNull("status");sub.put("createdBy",row.member);sub.put("createdAt",now().toString());sub.set("comments",json.createArrayNode());String parent=sub.path("parentId").asText();if(subIds.containsKey(parent))sub.put("parentId",subIds.get(parent));shiftDate(sub,"due",shift);});
        // A generated id must never overwrite an unrelated/restored record.
        if(mapper.selectById(newId)!=null)throw new ConflictException("下一次任务编号已存在，请检查后重试");
        tasks.replace(newId,json.convertValue(copy,TaskDto.class),row.member);
        jdbc.update("INSERT INTO task_labels(task_id,sub_id,revision,labels_json) SELECT ?,'',1,labels_json FROM task_labels WHERE task_id=? AND sub_id=''",newId,task.id());
        subIds.forEach((old,replacement)->jdbc.update("INSERT INTO task_labels(task_id,sub_id,revision,labels_json) SELECT ?,?,1,labels_json FROM task_labels WHERE task_id=? AND sub_id=?",newId,replacement,task.id(),old));
        r.revision=1;r.anchorDate=next.toString();
        jdbc.update("INSERT INTO task_time_rule(task_id,member_id,revision,body_json,active) VALUES(?,?,?,?,TRUE)",newId,row.member,1,write(r));
        jdbc.update("UPDATE task_time_rule SET next_task_id=?,active=FALSE,error_message=NULL WHERE task_id=?",newId,task.id());
    }
    private LocalDate completedDate(TaskDto task){try{return OffsetDateTime.parse(task.completedAt()).atZoneSameInstant(ZONE).toLocalDate();}catch(RuntimeException e){try{return LocalDateTime.parse(task.completedAt()).toLocalDate();}catch(RuntimeException ignored){return now().toLocalDate();}}}
    private void deliver(String id,String member,String key,TaskDto task,String title,String body){
        if(delivered(id,member,key))return;
        if(!notifications.publishToMember(member,new Card(title,"blue",List.of(),body,"打开任务","/todo?task="+task.id())))throw new IllegalStateException("提醒未写入通知中心");
        jdbc.update("INSERT INTO task_time_delivery(task_id,member_id,reminder_key,delivered_at) VALUES(?,?,?,?)",id,member,key,now());
    }
    private boolean delivered(String id,String member,String key){return jdbc.queryForObject("SELECT COUNT(*) FROM task_time_delivery WHERE task_id=? AND member_id=? AND reminder_key=?",Integer.class,id,member,key)>0;}
    private static String key(LocalDateTime anchor,int before){return anchor+":"+before;}
    private static void shiftDate(ObjectNode node,String field,long days){String value=node.path(field).asText("");if(!value.isBlank())node.put(field,TaskTimeCalendar.date(value).plusDays(days).toString());}
    private static void shiftTime(ObjectNode node,String field,long days){String value=node.path(field).asText("");if(!value.isBlank())node.put(field,LocalDateTime.parse(value).plusDays(days).toString());}
    private Stored stored(String id){var rows=jdbc.query("SELECT member_id,body_json,next_task_id,error_message FROM task_time_rule WHERE task_id=?",(rs,n)->new Stored(rs.getString(1),read(rs.getString(2)),rs.getString(3),rs.getString(4)),id);return rows.isEmpty()?null:rows.get(0);}
    private TaskTimeRule read(String raw){try{return json.readValue(raw,TaskTimeRule.class);}catch(Exception e){throw new IllegalStateException("时间规则读取失败",e);}}
    private String write(TaskTimeRule r){try{return json.writeValueAsString(r);}catch(Exception e){throw new IllegalStateException("时间规则保存失败",e);}}
    private void changed(String member){if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){changes.changed(member);}});}
}
