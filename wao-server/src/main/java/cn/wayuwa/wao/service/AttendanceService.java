package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.VaultCryptoService;
import cn.wayuwa.wao.domain.AttendanceConnectorEntity;
import cn.wayuwa.wao.domain.AttendanceDayEntity;
import cn.wayuwa.wao.dto.AttendanceConfigRequest;
import cn.wayuwa.wao.dto.AttendanceDayRequest;
import cn.wayuwa.wao.dto.AttendanceDto;
import cn.wayuwa.wao.mapper.AttendanceConnectorMapper;
import cn.wayuwa.wao.mapper.AttendanceDayMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 成员考勤的配置、同步、手工覆盖、工时统计与每日节奏联动。 */
@Service
public class AttendanceService {
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final int HISTORY_MONTHS = 12;
    private static final Set<String> DAY_TYPES = Set.of("WORKDAY", "LEAVE", "REST");
    private final AttendanceConnectorMapper connectors; private final AttendanceDayMapper days;
    private final VaultService vault; private final VaultCryptoService crypto; private final MokaAttendanceClient moka;
    private final WorkdayRhythmDashboardService rhythm; private final ObjectMapper json;

    public AttendanceService(AttendanceConnectorMapper connectors, AttendanceDayMapper days, VaultService vault,
            VaultCryptoService crypto, MokaAttendanceClient moka, WorkdayRhythmDashboardService rhythm, ObjectMapper json) {
        this.connectors=connectors; this.days=days; this.vault=vault; this.crypto=crypto; this.moka=moka; this.rhythm=rhythm; this.json=json;
    }

    /** 读取日期范围内的有效考勤、日工时、周均值与当前范围的日均值。 */
    public AttendanceDto attendance(String memberId, LocalDate from, LocalDate to) {
        validateRange(from, to, 366);
        Map<LocalDate, AttendanceDayEntity> stored = new LinkedHashMap<>();
        days.findRange(memberId, from, to).forEach(value -> stored.put(value.getAttendanceDate(), value));
        List<AttendanceDto.Day> result = new ArrayList<>();
        for (LocalDate date=from; !date.isAfter(to); date=date.plusDays(1)) result.add(toDay(memberId, date, stored.get(date)));
        List<AttendanceDto.Day> valid = result.stream().filter(this::valid).toList();
        BigDecimal total = valid.stream().map(AttendanceDto.Day::workHours).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        BigDecimal average = average(valid);
        Map<LocalDate,List<AttendanceDto.Day>> byWeek = new LinkedHashMap<>();
        result.forEach(day -> byWeek.computeIfAbsent(day.date().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), ignored -> new ArrayList<>()).add(day));
        List<AttendanceDto.Week> weeks = byWeek.entrySet().stream().map(entry -> {
            List<AttendanceDto.Day> weekValid=entry.getValue().stream().filter(this::valid).toList();
            String label=entry.getValue().stream().filter(AttendanceDto.Day::expectedWorkday).map(AttendanceDto.Day::workWeekLabel)
                    .filter(StringUtils::hasText).findFirst().orElse("自然周");
            return new AttendanceDto.Week(entry.getKey(), entry.getKey().plusDays(6), label, average(weekValid), weekValid.size());
        }).toList();
        return new AttendanceDto(from,to,configDto(connectors.selectById(memberId)),result,total,average,valid.size(),weeks);
    }

    /** 保存 Moka 连接设置；密码只校验密码本引用，绝不复制到考勤表。 */
    public AttendanceDto.Config saveConfig(String memberId, AttendanceConfigRequest request) {
        String username=trim(request.username()); String vaultId=blank(request.vaultItemId());
        if (request.enabled() && !StringUtils.hasText(username)) throw new BadRequestException("请填写 Moka 登录邮箱");
        if (request.enabled() && !StringUtils.hasText(vaultId)) throw new BadRequestException("请选择保存 Moka 密码的密码本条目");
        if (request.enabled() && !StringUtils.hasText(vault.revealSecret(vaultId, memberId))) throw new BadRequestException("所选密码本条目还没有密码");
        AttendanceConnectorEntity value=connectors.selectById(memberId); LocalDateTime now=LocalDateTime.now(ZONE);
        if (value==null) { value=new AttendanceConnectorEntity(); value.setMemberId(memberId); value.setProvider("MOKA"); value.setCreatedAt(now); }
        boolean changed=!java.util.Objects.equals(value.getUsername(),username)||!java.util.Objects.equals(value.getVaultItemId(),vaultId);
        value.setUsername(username); value.setVaultItemId(vaultId); value.setEnabled(request.enabled()); value.setUpdatedAt(now);
        if (changed) { value.setSessionCiphertext(null); value.setSessionNonce(null); value.setRemoteEmployeeId(null); }
        value.setStatus(request.enabled()?"READY":"DISABLED"); value.setLastError(null);
        boolean inserting=connectors.selectById(memberId)==null;
        if (inserting) connectors.insert(value); else connectors.updateById(value);
        if (changed&&!inserting) { connectors.resetHistoryBackfill(memberId,now); value.setHistorySyncedFrom(null); }
        return configDto(value);
    }

    /** 从 Moka 拉取一段日期并以幂等 upsert 落库；过期会话只重登一次，绝不强制踢人。 */
    public AttendanceDto sync(String memberId, LocalDate from, LocalDate to) {
        return sync(memberId, from, to, false);
    }

    /** 用户手动确认只用于本次初始登录，不持久保存，也不授权后续重登。 */
    public AttendanceDto sync(String memberId, LocalDate from, LocalDate to, boolean confirmSessionReplacement) {
        validateRange(from,to,62); AttendanceConnectorEntity connector=requireEnabled(memberId);
        connector.setStatus("SYNCING"); connector.setLastError(null); connector.setUpdatedAt(LocalDateTime.now(ZONE)); connectors.updateById(connector);
        try {
            MokaAttendanceClient.Session session=confirmSessionReplacement?login(memberId,connector,true):session(connector); String employeeId=confirmSessionReplacement?null:connector.getRemoteEmployeeId(); boolean relogged=false;
            try { if (!StringUtils.hasText(employeeId)) employeeId=moka.employeeId(session); }
            catch (MokaAttendanceClient.SessionExpiredException ex) {
                session=login(memberId,connector); relogged=true;
                try { employeeId=moka.employeeId(session); }
                catch (MokaAttendanceClient.SessionExpiredException repeated) { throw repeatedSessionExpiry(); }
            }
            connector.setRemoteEmployeeId(employeeId);
            for (LocalDate date=from; !date.isAfter(to); date=date.plusDays(1)) {
                MokaAttendanceClient.RemoteDay remote;
                try { remote=moka.day(session,employeeId,date); }
                catch (MokaAttendanceClient.SessionExpiredException ex) {
                    if (relogged) throw repeatedSessionExpiry();
                    session=login(memberId,connector); relogged=true;
                    try { employeeId=moka.employeeId(session); remote=moka.day(session,employeeId,date); }
                    catch (MokaAttendanceClient.SessionExpiredException repeated) { throw repeatedSessionExpiry(); }
                    connector.setRemoteEmployeeId(employeeId);
                }
                upsertRemote(memberId,remote);
            }
            LocalDateTime now=LocalDateTime.now(ZONE); connector.setStatus("OK"); connector.setLastSyncedAt(now); connector.setLastError(null); connector.setUpdatedAt(now); connectors.updateById(connector);
            return attendance(memberId,from,to);
        } catch (MokaAttendanceClient.SessionConfirmationRequiredException ex) {
            fail(connector,"NEEDS_CONFIRMATION",ex.getMessage()); throw ex;
        } catch (MokaAttendanceClient.AuthenticationRequiredException ex) {
            fail(connector,"NEEDS_LOGIN",ex.getMessage()); throw ex;
        } catch (RuntimeException ex) {
            fail(connector,"ERROR",clean(ex.getMessage())); throw ex;
        }
    }

    /** 保存某日模拟值，允许部分打卡以验收缺卡场景；请假日不参与平均值。 */
    public AttendanceDto.Day saveManual(String memberId, LocalDate date, AttendanceDayRequest request) {
        validateDate(date); String type=StringUtils.hasText(request.dayType())?request.dayType().trim().toUpperCase():"WORKDAY";
        if (!DAY_TYPES.contains(type)) throw new BadRequestException("考勤日类型只能是工作日、请假或休息日");
        LocalDateTime in=parseTime(date,request.clockInTime(),false); LocalDateTime out=parseTime(date,request.clockOutTime(),request.clockOutNextDay());
        if (in!=null&&out!=null&&!out.isAfter(in)) throw new BadRequestException("下班时间必须晚于上班时间");
        AttendanceDayEntity value=new AttendanceDayEntity(); LocalDateTime now=LocalDateTime.now(ZONE);
        value.setMemberId(memberId); value.setAttendanceDate(date); value.setManualClockInAt(in); value.setManualClockOutAt(out);
        value.setManualDayType(type); value.setManualOverride(true); value.setCreatedAt(now); value.setUpdatedAt(now); days.upsertManual(value);
        syncRhythm(memberId,date,in,out); return toDay(memberId,date,days.find(memberId,date));
    }

    /** 清除某日模拟值并恢复远端事实；每日节奏随有效值重新对齐。 */
    public AttendanceDto.Day clearManual(String memberId, LocalDate date) {
        validateDate(date); days.clearManual(memberId,date,LocalDateTime.now(ZONE)); AttendanceDayEntity value=days.find(memberId,date);
        syncRhythm(memberId,date,effectiveIn(value),effectiveOut(value)); return toDay(memberId,date,value);
    }

    /** 打开每日节奏时，若今天数据超过五分钟未刷新则尝试刷新；失败不阻断原面板。 */
    public void refreshTodayIfStale(String memberId, LocalDate date) {
        if (!date.equals(LocalDate.now(ZONE))) return; AttendanceConnectorEntity value=connectors.selectById(memberId);
        if (!automaticSyncAllowed(value)) return;
        if (value.getLastSyncedAt()!=null&&value.getLastSyncedAt().isAfter(LocalDateTime.now(ZONE).minusMinutes(5))) return;
        try { sync(memberId,date,date); } catch (RuntimeException ignored) { }
    }

    /** 提醒发送前确保考勤刷新时间不早于该提醒时间；失败时保留提醒链路，避免外部故障吞掉提醒。 */
    public void refreshBeforeReminder(String memberId, LocalDate date, LocalDateTime reminderAt) {
        AttendanceConnectorEntity value=connectors.selectById(memberId);
        if(!automaticSyncAllowed(value)) return;
        if(value.getLastSyncedAt()!=null&&!value.getLastSyncedAt().isBefore(reminderAt)) return;
        try{sync(memberId,date,date);}catch(RuntimeException ignored){ }
    }

    /** 定时同步入口使用的已启用成员列表。 */
    public List<String> enabledMembers() { return connectors.selectList(new LambdaQueryWrapper<AttendanceConnectorEntity>().eq(AttendanceConnectorEntity::getEnabled,true)).stream().filter(this::automaticSyncAllowed).map(AttendanceConnectorEntity::getMemberId).toList(); }

    /** 每次在分布式租约保护下向前补齐一个自然月，直至覆盖包含本月在内的近十二个月。 */
    public void backfillNextPeriod(String memberId, LocalDate today) {
        AttendanceConnectorEntity current=connectors.selectById(memberId); LocalDate target=historyTarget(today);
        if(!automaticSyncAllowed(current)||(current.getHistorySyncedFrom()!=null&&!current.getHistorySyncedFrom().isAfter(target))) return;
        LocalDateTime now=LocalDateTime.now(ZONE); String token=UUID.randomUUID().toString();
        if(connectors.claimHistoryBackfill(memberId,token,now,now.minusMinutes(20))!=1) return;
        try {
            current=connectors.selectById(memberId); LocalDate cursor=current==null?null:current.getHistorySyncedFrom();
            if(cursor!=null&&!cursor.isAfter(target)) return;
            LocalDate from; LocalDate to;
            if(cursor==null) { from=today.withDayOfMonth(1); to=today.minusDays(1); }
            else { to=cursor.minusDays(1); from=to.withDayOfMonth(1); if(from.isBefore(target)) from=target; }
            if(!to.isBefore(from)) sync(memberId,from,to);
            connectors.advanceHistoryBackfill(memberId,from,token,LocalDateTime.now(ZONE));
        } finally {
            connectors.releaseHistoryBackfill(memberId,token);
        }
    }

    private void upsertRemote(String memberId,MokaAttendanceClient.RemoteDay remote) {
        AttendanceDayEntity value=new AttendanceDayEntity(); LocalDateTime now=LocalDateTime.now(ZONE);
        value.setMemberId(memberId); value.setAttendanceDate(remote.date()); value.setRemoteClockInAt(remote.clockInAt()); value.setRemoteClockOutAt(remote.clockOutAt());
        value.setRemoteDayType(remote.dayType()); value.setRemoteAnomaly(blank(remote.anomaly())); value.setManualOverride(false); value.setSyncedAt(now); value.setCreatedAt(now); value.setUpdatedAt(now);
        days.upsertRemote(value); AttendanceDayEntity stored=days.find(memberId,remote.date()); syncRhythm(memberId,remote.date(),effectiveIn(stored),effectiveOut(stored));
    }
    private void syncRhythm(String memberId,LocalDate date,LocalDateTime in,LocalDateTime out) { rhythm.syncAttendanceCompletion(memberId,date,"CLOCK_IN",in); rhythm.syncAttendanceCompletion(memberId,date,"CLOCK_OUT",out); }
    private AttendanceDto.Day toDay(String memberId,LocalDate date,AttendanceDayEntity value) {
        WorkdayRhythmDashboardService.WorkdayInfo workday=rhythm.workdayInfo(memberId,date); LocalDateTime in=effectiveIn(value),out=effectiveOut(value); String type=effectiveType(value);
        if (!StringUtils.hasText(type)) type=workday.active()?"WORKDAY":"REST"; String anomaly=value==null?null:value.getRemoteAnomaly();
        LocalDate today=LocalDate.now(ZONE);
        if (date.isAfter(today)) anomaly=null; else if (workday.active()&&!"LEAVE".equals(type)) {
            if (date.equals(today)&&in==null&&out==null) anomaly=null;
            else if (in==null&&out==null) anomaly=join(anomaly,"未打卡");
            else if (in==null) anomaly=join(anomaly,"缺上班卡");
            else if (out==null) anomaly=join(anomaly,"缺下班卡");
        }
        return new AttendanceDto.Day(date,string(in),string(out),type,anomaly,value!=null&&Boolean.TRUE.equals(value.getManualOverride()),hours(in,out,type),workday.active(),workday.label());
    }
    private boolean valid(AttendanceDto.Day day){return !"LEAVE".equals(day.dayType())&&day.workHours()!=null;}
    private BigDecimal average(List<AttendanceDto.Day> valid){return valid.isEmpty()?BigDecimal.ZERO.setScale(2):valid.stream().map(AttendanceDto.Day::workHours).reduce(BigDecimal.ZERO,BigDecimal::add).divide(BigDecimal.valueOf(valid.size()),2,RoundingMode.HALF_UP);}
    private BigDecimal hours(LocalDateTime in,LocalDateTime out,String type){if(in==null||out==null||"LEAVE".equals(type)||!out.isAfter(in))return null; long minutes=Math.max(0,Duration.between(in,out).toMinutes()-60); return BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60),2,RoundingMode.HALF_UP);}
    private AttendanceDto.Config configDto(AttendanceConnectorEntity value){LocalDate target=historyTarget(LocalDate.now(ZONE));if(value==null)return new AttendanceDto.Config(false,null,null,"UNCONFIGURED",null,null,null,target,false);LocalDate synced=value.getHistorySyncedFrom();return new AttendanceDto.Config(Boolean.TRUE.equals(value.getEnabled()),value.getUsername(),value.getVaultItemId(),value.getStatus(),string(value.getLastSyncedAt()),value.getLastError(),synced,target,synced!=null&&!synced.isAfter(target));}
    private AttendanceConnectorEntity requireEnabled(String memberId){AttendanceConnectorEntity value=connectors.selectById(memberId); if(value==null||!Boolean.TRUE.equals(value.getEnabled()))throw new BadRequestException("请先在考勤设置中启用 Moka 同步"); if(!StringUtils.hasText(value.getVaultItemId()))throw new BadRequestException("请先选择 Moka 密码本条目"); return value;}
    private MokaAttendanceClient.Session session(AttendanceConnectorEntity value){if(value.getSessionCiphertext()==null||value.getSessionNonce()==null)return login(value.getMemberId(),value); try{return json.readValue(crypto.decrypt(value.getSessionCiphertext(),value.getSessionNonce()),MokaAttendanceClient.Session.class);}catch(Exception ex){return login(value.getMemberId(),value);}}
    private MokaAttendanceClient.Session login(String memberId,AttendanceConnectorEntity value){return login(memberId,value,false);}
    private MokaAttendanceClient.Session login(String memberId,AttendanceConnectorEntity value,boolean confirmSessionReplacement){String password=vault.revealSecret(value.getVaultItemId(),memberId); if(!StringUtils.hasText(password))throw new MokaAttendanceClient.AuthenticationRequiredException("密码本里的 Moka 条目还没有密码"); MokaAttendanceClient.Session session=confirmSessionReplacement?moka.login(value.getUsername(),password,true):moka.login(value.getUsername(),password); try{VaultCryptoService.Encrypted encrypted=crypto.encrypt(json.writeValueAsString(session));value.setSessionCiphertext(encrypted.ciphertext());value.setSessionNonce(encrypted.nonce());connectors.updateById(value);return session;}catch(Exception ex){throw new IllegalStateException("Moka 会话保存失败",ex);}}
    private void fail(AttendanceConnectorEntity value,String status,String message){value.setStatus(status);value.setLastError(message);value.setUpdatedAt(LocalDateTime.now(ZONE));connectors.updateById(value);}
    private boolean automaticSyncAllowed(AttendanceConnectorEntity value){return value!=null&&Boolean.TRUE.equals(value.getEnabled())&&!Set.of("NEEDS_LOGIN","NEEDS_CONFIRMATION").contains(value.getStatus()==null?"":value.getStatus());}
    private MokaAttendanceClient.AuthenticationRequiredException repeatedSessionExpiry(){return new MokaAttendanceClient.AuthenticationRequiredException("Moka 会话反复失效，已停止自动登录；请先在浏览器完成一次登录后手动重试");}
    private LocalDateTime effectiveIn(AttendanceDayEntity value){return value==null?null:Boolean.TRUE.equals(value.getManualOverride())?value.getManualClockInAt():value.getRemoteClockInAt();}
    private LocalDateTime effectiveOut(AttendanceDayEntity value){return value==null?null:Boolean.TRUE.equals(value.getManualOverride())?value.getManualClockOutAt():value.getRemoteClockOutAt();}
    private String effectiveType(AttendanceDayEntity value){return value==null?null:Boolean.TRUE.equals(value.getManualOverride())?value.getManualDayType():value.getRemoteDayType();}
    private LocalDateTime parseTime(LocalDate date,String raw,boolean nextDay){if(!StringUtils.hasText(raw))return null;try{return date.plusDays(nextDay?1:0).atTime(LocalTime.parse(raw.trim()));}catch(Exception ex){throw new BadRequestException("请填写有效的打卡时间");}}
    private void validateRange(LocalDate from,LocalDate to,long max){if(from==null||to==null||to.isBefore(from)||ChronoUnit.DAYS.between(from,to)>max)throw new BadRequestException("考勤日期范围不正确或过大");}
    private void validateDate(LocalDate date){if(date==null||Math.abs(ChronoUnit.DAYS.between(LocalDate.now(ZONE),date))>366)throw new BadRequestException("只能维护前后 366 天内的考勤");}
    private LocalDate historyTarget(LocalDate today){return today.minusMonths(HISTORY_MONTHS-1L).withDayOfMonth(1);}
    private static String string(LocalDateTime value){return value==null?null:value.toString();} private static String trim(String v){return v==null?"":v.trim();} private static String blank(String v){String x=trim(v);return x.isEmpty()?null:x;}
    private static String join(String left,String right){return StringUtils.hasText(left)?left+"、"+right:right;} private static String clean(String v){if(!StringUtils.hasText(v))return "Moka 同步失败";return v.length()>500?v.substring(0,500):v;}
}
