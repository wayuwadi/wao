package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.*;
import cn.wayuwa.wao.feishu.FeishuWebhookNotifier.Card;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoutineService {
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static class Item {
        public String id, kind = "countdown", name = "", date = "", emoji = "", note = "", status = "active";
        public long revision;
        public String color = "primary", style = "plain";
        public boolean pinned;
        public String category = "countdown", calendar = "solar", repeat = "none", direction = "down";
        public boolean showAge, showToday = true;
        public List<Integer> remindBefore = new ArrayList<>();
        public String reminderTime = "09:00";
        public String frequency = "daily", group = "其他", unit = "次";
        public List<Integer> weekdays = new ArrayList<>(List.of(1,2,3,4,5,6,7));
        public int weeklyTarget = 3, intervalDays = 2, target = 1, durationDays;
        public boolean autoLog;
        public List<String> reminderTimes = new ArrayList<>();
    }
    public record Log(String habitId, String date, long revision, int amount, int target, String note) {}
    public record Checkin(long revision, int amount, String note) {}
    public record Summary(String id, String occurrence, Long days, String lunarLabel, Integer age,
                          long total, long monthCount, int monthRate, int streak, int bestStreak, String streakUnit,
                          long weekCount, List<String> scheduledDates) {}
    public record State(String today, String month, List<Item> items, List<Summary> summaries, List<Log> logs) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final InternalNotificationService notifications;
    private final Clock clock;
    @Autowired public RoutineService(JdbcTemplate jdbc, ObjectMapper json, InternalNotificationService notifications) {
        this(jdbc,json,notifications,Clock.system(ZONE));
    }
    RoutineService(JdbcTemplate jdbc,ObjectMapper json,InternalNotificationService notifications,Clock clock) {
        this.jdbc=jdbc;this.json=json;this.notifications=notifications;this.clock=clock;
    }
    LocalDate today() { return LocalDate.now(clock.withZone(ZONE)); }
    public State state(String member,String month) {
        requireMember(member);
        YearMonth window;
        try { window = month == null ? YearMonth.from(today()) : YearMonth.parse(month); }
        catch (RuntimeException e) { throw new BadRequestException("月份不合法"); }
        if (window.getYear()<2000 || window.getYear()>2100) throw new BadRequestException("月份超出范围");
        List<Item> items = jdbc.query("SELECT body_json FROM personal_routine WHERE member_id = ?",(rs,n)->read(rs.getString(1)),member);
        List<Log> all = jdbc.query("SELECT c.* FROM habit_checkin c JOIN personal_routine r ON r.id=c.habit_id WHERE r.member_id=?",(rs,n)->log(rs),member);
        List<Summary> summaries = new ArrayList<>();
        for (Item item : items) summaries.add(summary(item, all.stream().filter(l->l.habitId().equals(item.id)).toList(),window,today()));
        LocalDate recent = today().minusDays(6);
        List<Log> visible = all.stream().filter(l -> YearMonth.from(RoutineCalendar.date(l.date())).equals(window)
                || !RoutineCalendar.date(l.date()).isBefore(recent)).toList();
        return new State(today().toString(),window.toString(),items,summaries,visible);
    }
    @Transactional public Item save(String member,String id,Item request) {
        lockMember(member); checkId(id);
        if(request==null) throw new BadRequestException("请填写内容");
        request.id=id; validate(request);
        List<Item> rows = jdbc.query("SELECT body_json FROM personal_routine WHERE id=? AND member_id=?",(rs,n)->read(rs.getString(1)),id,member);
        if(rows.isEmpty()) {
            if(request.revision!=0) throw new NotFoundException("记录不存在");
            if(jdbc.queryForObject("SELECT COUNT(*) FROM personal_routine WHERE member_id=?",Integer.class,member)>=500) throw new BadRequestException("最多保留 500 条记录，请先整理现有记录");
            if(jdbc.queryForObject("SELECT COUNT(*) FROM personal_routine WHERE id=?",Integer.class,id)>0) throw new ConflictException("记录编号冲突，请重新创建");
            request.revision=1;
            jdbc.update("INSERT INTO personal_routine(id,member_id,kind,status,reminder_enabled,body_json) VALUES(?,?,?,?,?,?)",id,member,request.kind,request.status,hasReminders(request),write(request));
        } else {
            Item current=rows.get(0);
            if(!current.kind.equals(request.kind)) throw new BadRequestException("记录类型不能更改");
            long revision=request.revision; request.revision=current.revision;
            if(revision!=current.revision) {
                if(revision+1==current.revision && write(request).equals(write(current))) return current;
                throw new ConflictException("记录已在其他页面更新，草稿已保留，请重新读取后比较");
            }
            request.revision++;
            jdbc.update("UPDATE personal_routine SET status=?,reminder_enabled=?,body_json=? WHERE id=? AND member_id=?",request.status,hasReminders(request),write(request),id,member);
        }
        return request;
    }
    public Item owned(String member,String id) {
        requireMember(member);
        var items=jdbc.query("SELECT body_json FROM personal_routine WHERE id=? AND member_id=?",(rs,n)->read(rs.getString(1)),id,member);
        if(items.isEmpty()) throw new NotFoundException("记录不存在或无权查看");
        return items.get(0);
    }
    @Transactional public Log checkin(String member,String id,String dayValue,Checkin request) {
        lockMember(member);Item item=owned(member,id);
        LocalDate day=parseDate(dayValue);
        if(!item.kind.equals("habit") || !item.status.equals("active")) throw new BadRequestException("请先恢复该习惯再打卡");
        if(day.isAfter(today()) || !RoutineCalendar.scheduled(item,day)) throw new BadRequestException("只能在已到来的计划日期打卡");
        if(request==null || request.amount()<0 || request.amount()>1_000_000) throw new BadRequestException("打卡数量不合法");
        String note=bounded(request.note(),5000,"打卡日志");
        List<Log> current=jdbc.query("SELECT * FROM habit_checkin WHERE habit_id=? AND checkin_date=?",(rs,n)->log(rs),id,day);
        long revision=current.isEmpty()?0:current.get(0).revision();
        if(request.revision()!=revision) {
            if(!current.isEmpty() && request.revision()+1==revision && request.amount()==current.get(0).amount() && note.equals(current.get(0).note())) return current.get(0);
            throw new ConflictException("这一天已在其他页面更新，日志草稿已保留，请重新读取后比较");
        }
        int goal=current.isEmpty()?item.target:current.get(0).target();
        if(current.isEmpty()) jdbc.update("INSERT INTO habit_checkin(habit_id,checkin_date,revision,amount,target,note) VALUES(?,?,?,?,?,?)",id,day,1,request.amount(),goal,note);
        else jdbc.update("UPDATE habit_checkin SET revision=?,amount=?,note=? WHERE habit_id=? AND checkin_date=?",revision+1,request.amount(),note,id,day);
        return new Log(id,day.toString(),revision+1,request.amount(),goal,note);
    }
    static Summary summary(Item item,List<Log> logs,YearMonth month,LocalDate today) {
        if(item.kind.equals("countdown")) {
            LocalDate base=RoutineCalendar.date(item.date), next=RoutineCalendar.next(item,today);
            Long days=item.direction.equals("up") ? ChronoUnit.DAYS.between(base,today) : next==null?null:ChronoUnit.DAYS.between(today,next);
            Integer age=item.showAge && !base.isAfter(today) ? Period.between(base,today).getYears() : null;
            return new Summary(item.id,next==null?null:next.toString(),days,RoutineCalendar.lunarLabel(base),age,0,0,0,0,0,"天",0,List.of());
        }
        Set<LocalDate> done=new HashSet<>();
        logs.stream().filter(l->l.amount()>=l.target()).map(l->RoutineCalendar.date(l.date())).filter(d->!d.isAfter(today)).forEach(done::add);
        LocalDate start=RoutineCalendar.date(item.date), first=month.atDay(1), last=month.atEndOfMonth();
        long monthDone=done.stream().filter(d->YearMonth.from(d).equals(month)).count();
        long weekCount=done.stream().filter(d->!d.isBefore(RoutineCalendar.monday(today))).count();
        List<String> planned=new ArrayList<>();
        for(LocalDate d=first;!d.isAfter(last);d=d.plusDays(1)) if(RoutineCalendar.scheduled(item,d)) planned.add(d.toString());
        // Planned weekday/interval dates define streaks; weekly targets define whole-week streaks.
        int run=0,best=0,current=0,expected=0;
        if(item.frequency.equals("weekly")) {
            for(LocalDate week=RoutineCalendar.monday(start);!week.isAfter(today);week=week.plusWeeks(1)) {
                long count=0;int available=0,monthAvailable=0;
                for(int i=0;i<7;i++) {LocalDate d=week.plusDays(i);if(!d.isAfter(today)&&RoutineCalendar.scheduled(item,d)){available++;if(done.contains(d))count++;if(YearMonth.from(d).equals(month))monthAvailable++;}}
                expected+=Math.min(item.weeklyTarget,monthAvailable);
                if(available==0)continue;
                if(count>=item.weeklyTarget){run++;best=Math.max(best,run);current=run;}
                else if(week.plusDays(6).isBefore(today)){run=0;current=0;}
                else current=run;
            }
        } else {
            for(LocalDate d=start;!d.isAfter(today);d=d.plusDays(1)) if(RoutineCalendar.scheduled(item,d)) {
                if(YearMonth.from(d).equals(month))expected++;
                if(done.contains(d)){run++;best=Math.max(best,run);current=run;}
                else if(d.isBefore(today)){run=0;current=0;}
            }
        }
        return new Summary(item.id,null,null,"",null,done.size(),monthDone,expected==0?0:(int)Math.min(100,Math.round(monthDone*100.0/expected)),current,best,item.frequency.equals("weekly")?"周":"次",weekCount,planned);
    }
    public List<String> reminderIds() { return jdbc.queryForList("SELECT id FROM personal_routine WHERE status='active' AND reminder_enabled=TRUE",String.class); }
    @Transactional public void deliverDue(String id) {
        List<String> owners=jdbc.queryForList("SELECT member_id FROM personal_routine WHERE id=?",String.class,id);if(owners.isEmpty())return;
        String member=owners.get(0);lockMember(member);Item item=owned(member,id);
        if(!item.status.equals("active"))return;
        LocalDateTime now=LocalDateTime.now(clock.withZone(ZONE));LocalDate day=now.toLocalDate();
        if(item.kind.equals("countdown")) {
            if(LocalTime.parse(item.reminderTime).isAfter(now.toLocalTime()))return;
            for(Integer before:item.remindBefore){LocalDate occurrence=day.plusDays(before);if(occurrence.equals(RoutineCalendar.next(item,occurrence)))
                notifyOnce(item,member,day,"d"+before,"倒数日 · "+item.name,(before==0?"今天":before+" 天后")+"是 "+item.name+"（"+occurrence+"）。","/countdowns");}
        } else if(RoutineCalendar.scheduled(item,day)) {
            Boolean completed=jdbc.query("SELECT amount,target FROM habit_checkin WHERE habit_id=? AND checkin_date=?",rs->rs.next()&&rs.getInt(1)>=rs.getInt(2),id,day);
            if(completed)return;
            if(item.frequency.equals("weekly")) {
                Integer days=jdbc.queryForObject("SELECT COUNT(*) FROM habit_checkin WHERE habit_id=? AND checkin_date BETWEEN ? AND ? AND amount>=target",Integer.class,id,RoutineCalendar.monday(day),day);
                if(days>=item.weeklyTarget)return;
            }
            for(String time:item.reminderTimes) if(!LocalTime.parse(time).isAfter(now.toLocalTime()))
                notifyOnce(item,member,day,time,"习惯提醒 · "+item.name,"今天的目标："+item.target+" "+item.unit+"。","/habits?habit="+id);
        }
    }
    private void notifyOnce(Item item,String member,LocalDate day,String slot,String title,String body,String link) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM personal_routine_delivery WHERE routine_id=? AND delivery_date=? AND slot=?",Integer.class,item.id,day,slot)>0)return;
        notifications.publishToMember(member,new Card(title,"blue",List.of(),body,"查看",link));
        jdbc.update("INSERT INTO personal_routine_delivery(routine_id,delivery_date,slot) VALUES(?,?,?)",item.id,day,slot);
    }
    private void validate(Item i) {
        requireSet(i.kind,Set.of("countdown","habit"),"类型");requireSet(i.status,Set.of("active","archived","trash"),"状态");
        i.name=bounded(i.name,120,"名称").trim();if(i.name.isEmpty())throw new BadRequestException("请输入名称");
        i.note=bounded(i.note,5000,"备注");i.emoji=bounded(i.emoji,16,"图标");parseDate(i.date);
        requireSet(i.color,Set.of("primary","green","orange","pink","purple"),"颜色");requireSet(i.style,Set.of("plain","soft","outline"),"样式");
        if(i.kind.equals("countdown")) {
            requireSet(i.category,Set.of("countdown","anniversary","birthday","holiday"),"日期类型");
            requireSet(i.calendar,Set.of("solar","lunar"),"历法");requireSet(i.repeat,Set.of("none","weekly","monthly","yearly"),"重复方式");requireSet(i.direction,Set.of("down","up"),"计时方式");
            if(i.calendar.equals("lunar")&&!Set.of("none","yearly").contains(i.repeat))throw new BadRequestException("农历支持不重复或每年重复");
            if(i.remindBefore==null || i.remindBefore.size()>5 || i.remindBefore.stream().anyMatch(d->d==null||d<0||d>365))throw new BadRequestException("提前提醒应为 0 至 365 天，最多 5 个");
            i.remindBefore=i.remindBefore.stream().distinct().sorted().toList();validTime(i.reminderTime);
        } else {
            if(parseDate(i.date).getYear()<2000)throw new BadRequestException("习惯开始日期应为 2000 年或以后");
            requireSet(i.frequency,Set.of("daily","weekly","interval"),"频率");
            if(i.weekdays==null || i.weekdays.isEmpty() || i.weekdays.size()>7 || i.weekdays.stream().anyMatch(d->d==null||d<1||d>7))throw new BadRequestException("请选择有效星期");
            i.weekdays=i.weekdays.stream().distinct().sorted().toList();
            if(i.weeklyTarget<1||i.weeklyTarget>7||i.intervalDays<1||i.intervalDays>365||i.target<1||i.target>1_000_000||i.durationDays<0||i.durationDays>3650)throw new BadRequestException("习惯目标或计划范围不合法");
            i.group=bounded(i.group,40,"分组").trim();i.unit=bounded(i.unit,16,"单位").trim();if(i.unit.isEmpty())i.unit="次";
            if(i.reminderTimes==null||i.reminderTimes.size()>5)throw new BadRequestException("最多设置 5 个提醒时间");
            i.reminderTimes.forEach(RoutineService::validTime);i.reminderTimes=i.reminderTimes.stream().distinct().sorted().toList();
        }
    }
    private static boolean hasReminders(Item i){return i.kind.equals("countdown")?!i.remindBefore.isEmpty():!i.reminderTimes.isEmpty();}
    private static void validTime(String v){try{if(v==null||!v.matches("\\d{2}:\\d{2}"))throw new IllegalArgumentException();LocalTime.parse(v);}catch(RuntimeException e){throw new BadRequestException("提醒时间不合法");}}
    private static void requireSet(String v,Set<String> options,String label){if(v==null||!options.contains(v))throw new BadRequestException(label+"不合法");}
    private static String bounded(String v,int max,String label){if(v==null)return "";if(v.length()>max)throw new BadRequestException(label+"过长");return v;}
    private static LocalDate parseDate(String v){try{LocalDate d=RoutineCalendar.date(v);if(d.getYear()<1900||d.getYear()>2100)throw new IllegalArgumentException();return d;}catch(RuntimeException e){throw new BadRequestException("请选择 1900 至 2100 年的有效日期");}}
    private static void checkId(String id){if(id==null||!id.matches("[a-f0-9]{32}"))throw new BadRequestException("记录编号不合法");}
    private static void requireMember(String member){if(member==null||member.isBlank())throw new BadRequestException("请先登录");}
    private void lockMember(String member){requireMember(member);if(jdbc.queryForList("SELECT id FROM member WHERE id=? FOR UPDATE",String.class,member).isEmpty())throw new BadRequestException("成员不存在");}
    private Item read(String value){try{return json.readValue(value,Item.class);}catch(Exception e){throw new IllegalStateException("记录读取失败",e);}}
    private String write(Item value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("记录保存失败",e);}}
    private static Log log(java.sql.ResultSet rs)throws java.sql.SQLException{return new Log(rs.getString("habit_id"),rs.getDate("checkin_date").toLocalDate().toString(),rs.getLong("revision"),rs.getInt("amount"),rs.getInt("target"),rs.getString("note"));}
}
