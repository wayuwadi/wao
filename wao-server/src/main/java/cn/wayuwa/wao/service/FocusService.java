package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Personal focus records. Finishing and creating the note commit together. */
@Service
public class FocusService {
    public static class Session {
        public String id, mode, status, taskId, subId, taskTitle, note, noteId, habitId;
        public long revision, startedAt, runningSince, elapsedMs, plannedMs, endedAt;
    }
    public record Start(String mode, int minutes, String taskId, String subId, String note, String habitId) {
        public Start(String mode,int minutes,String taskId,String subId,String note){this(mode,minutes,taskId,subId,note,null);}
    }
    public record Change(long revision, String action, String note) {}
    public record Stats(long todayCount, long todayMs, long totalCount, long totalMs) {}
    public record State(long serverNow, Session active, List<Session> history, Stats stats) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TaskService tasks;
    private final KnowledgeService knowledge;
    private final Clock clock;
    private final RoutineService routines;
    @org.springframework.beans.factory.annotation.Autowired
    public FocusService(JdbcTemplate jdbc, ObjectMapper json, TaskService tasks, KnowledgeService knowledge, RoutineService routines) {
        this(jdbc, json, tasks, knowledge, Clock.systemUTC(),routines);
    }
    FocusService(JdbcTemplate jdbc, ObjectMapper json, TaskService tasks, KnowledgeService knowledge, Clock clock) {
        this(jdbc,json,tasks,knowledge,clock,null);
    }
    FocusService(JdbcTemplate jdbc, ObjectMapper json, TaskService tasks, KnowledgeService knowledge, Clock clock,RoutineService routines) {
        this.jdbc=jdbc; this.json=json; this.tasks=tasks; this.knowledge=knowledge; this.clock=clock;
        this.routines=routines;
    }
    public State state(String member) {
        requireMember(member);
        var active=read("SELECT body_json FROM focus_session WHERE active_member = ?", member);
        var history=read("SELECT body_json FROM focus_session WHERE member_id = ? AND ended_at IS NOT NULL ORDER BY ended_at DESC LIMIT 200", member);
        history.forEach(s -> { if(s.note.length()>500) s.note=s.note.substring(0,500)+"…"; });
        long midnight=LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai"))).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        Stats stats=jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN ended_at >= ? THEN 1 ELSE 0 END),0), COALESCE(SUM(CASE WHEN ended_at >= ? THEN elapsed_ms ELSE 0 END),0), COUNT(*), COALESCE(SUM(elapsed_ms),0) FROM focus_session WHERE member_id = ? AND ended_at IS NOT NULL AND mode <> 'break'",
            (rs,n)->new Stats(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4)), midnight,midnight,member);
        return new State(clock.millis(), active.isEmpty()?null:active.get(0), history, stats);
    }
    @Transactional
    public Session start(String id, Start req, String member) {
        lockMember(member);
        if(id==null || !id.matches("[a-f0-9]{32}")) throw new BadRequestException("专注记录编号不合法");
        var previous=read("SELECT body_json FROM focus_session WHERE id = ? AND member_id = ?",id,member);
        if(!previous.isEmpty()) return previous.get(0);
        if(req==null || !Set.of("pomodoro","stopwatch","break").contains(Objects.toString(req.mode(),""))) throw new BadRequestException("计时模式不合法");
        if(req.minutes()<1 || req.minutes()>180) throw new BadRequestException("时长应在 1 至 180 分钟之间");
        if(!read("SELECT body_json FROM focus_session WHERE active_member = ?",member).isEmpty()) throw new ConflictException("已有进行中的专注，请先继续或结束它");
        Session s=new Session(); s.id=id;s.mode=req.mode();s.status="running";s.revision=1;
        s.note=checkedNote(req.note());s.taskId=blank(req.taskId());s.subId=blank(req.subId());s.habitId=blank(req.habitId());s.taskTitle="";
        if(!s.habitId.isEmpty()) {
            if(!s.taskId.isEmpty()||!s.subId.isEmpty())throw new BadRequestException("一次专注只能关联一个任务或习惯");
            RoutineService.Item habit=routines.owned(member,s.habitId);
            if(!habit.kind.equals("habit")||!habit.status.equals("active"))throw new BadRequestException("习惯已归档或不可用");
            s.taskTitle=habit.name;
        }
        if(!s.taskId.isEmpty()) {
            TaskDto task=tasks.get(s.taskId,member);s.taskTitle=task.title();
            if(!s.subId.isEmpty()) {
                List<Step> subs=task.subs()==null?List.of():task.subs(); boolean found=false;
                for(int i=0;i<subs.size();i++) { Step sub=subs.get(i);String key=sub.id()==null||sub.id().isBlank()?task.id()+"#"+i:sub.id();
                    if(key.equals(s.subId)){s.taskTitle=sub.text();found=true;break;}
                }
                if(!found) throw new NotFoundException("关联子任务已不存在");
            }
        } else if(!s.subId.isEmpty()) throw new BadRequestException("子任务需要所属任务");
        s.startedAt=clock.millis();s.runningSince=s.startedAt;s.plannedMs=req.mode().equals("stopwatch")?0:req.minutes()*60_000L;
        jdbc.update("INSERT INTO focus_session(id,member_id,active_member,mode,elapsed_ms,body_json) VALUES(?,?,?,?,?,?)",id,member,member,s.mode,0,write(s));
        return s;
    }
    @Transactional
    public Session change(String id, Change req, String member) {
        lockMember(member);
        var found=read("SELECT body_json FROM focus_session WHERE id = ? AND member_id = ? FOR UPDATE",id,member);
        if(found.isEmpty()) throw new NotFoundException("专注记录不存在");
        Session s=found.get(0);
        if(req==null || !Set.of("pause","resume","save","finish").contains(Objects.toString(req.action(),""))) throw new BadRequestException("专注操作不合法");
        if("finished".equals(s.status) && "finish".equals(req.action())) return s;
        if("finished".equals(s.status) || req.revision()!=s.revision) throw new ConflictException("专注已在其他页面更新，已保留你的笔记，请重新读取后再试");
        s.note=checkedNote(req.note());long now=clock.millis();
        boolean finishing=req.action().equals("finish") || (s.status.equals("running") && s.plannedMs>0 && elapsed(s,now)>=s.plannedMs);
        if(!finishing && req.action().equals("pause") && s.status.equals("running")) {s.elapsedMs=elapsed(s,now);s.runningSince=0;s.status="paused";}
        if(!finishing && req.action().equals("resume") && s.status.equals("paused")) {s.runningSince=now;s.status="running";}
        if(finishing) {
            long end=now;
            if(s.status.equals("running") && s.plannedMs>0 && elapsed(s,now)>=s.plannedMs) end=s.runningSince+Math.max(0,s.plannedMs-s.elapsedMs);
            s.elapsedMs=elapsed(s,now);s.runningSince=0;s.status="finished";s.endedAt=end;
            if(!s.mode.equals("break") && s.note.codePoints().anyMatch(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c))) {
                s.noteId=UUID.randomUUID().toString().replace("-","");
                List<DocBlockDto> blocks=List.of(new DocBlockDto(null,null,s.note,null,null));
                CreateNoteRequest note=new CreateNoteRequest(null,blocks,null,List.of("番茄钟"),false,false,false,null,true);
                NoteAiManagedService.withoutAuto(member,()->knowledge.createNote(s.noteId,note,member));
            }
        }
        s.revision++;
        jdbc.update("UPDATE focus_session SET active_member=?,elapsed_ms=?,ended_at=?,body_json=? WHERE id=? AND member_id=?",s.status.equals("finished")?null:member,s.elapsedMs,s.endedAt==0?null:s.endedAt,write(s),id,member);
        return s;
    }
    static long elapsed(Session s,long now) {long value=s.elapsedMs+(s.status.equals("running")?Math.max(0,now-s.runningSince):0);return s.plannedMs>0?Math.min(value,s.plannedMs):value;}
    private void requireMember(String member){if(member==null||member.isBlank())throw new NotFoundException("请先登录");}
    private void lockMember(String member){requireMember(member);if(jdbc.queryForList("SELECT id FROM member WHERE id = ? FOR UPDATE",String.class,member).isEmpty())throw new NotFoundException("成员不存在");}
    private String checkedNote(String note){String value=blank(note);if(value.length()>50_000)throw new BadRequestException("专注笔记不能超过 50000 字");return value;}
    private String blank(String value){return value==null?"":value;}
    private List<Session> read(String sql,Object...args){return jdbc.query(sql,(rs,n)->{try{return json.readValue(rs.getString(1),Session.class);}catch(Exception e){throw new IllegalStateException(e);}},args);}
    private String write(Session s){try{return json.writeValueAsString(s);}catch(Exception e){throw new IllegalStateException(e);}}
}
