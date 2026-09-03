package cn.wayuwa.wao.service;
import java.time.LocalDate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
/** 每十五分钟刷新当天考勤；单成员失败不会影响其他成员。 */
@Component public class AttendanceSyncScheduler {
    private final AttendanceService attendance;
    public AttendanceSyncScheduler(AttendanceService attendance){this.attendance=attendance;}
    @Scheduled(cron="0 */15 * * * *",zone="Asia/Shanghai") public void syncToday(){LocalDate today=LocalDate.now(AttendanceService.ZONE);for(String member:attendance.enabledMembers()){try{attendance.sync(member,today,today);}catch(RuntimeException ignored){}}}
}
