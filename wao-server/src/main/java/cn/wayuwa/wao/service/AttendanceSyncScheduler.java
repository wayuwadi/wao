package cn.wayuwa.wao.service;
import java.time.LocalDate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/** 每十五分钟刷新当天考勤，并在成功后按月渐进补齐历史；单成员失败不会影响其他成员。 */
@Component public class AttendanceSyncScheduler {
    private static final Logger log = LoggerFactory.getLogger(AttendanceSyncScheduler.class);
    private final AttendanceService attendance;
    public AttendanceSyncScheduler(AttendanceService attendance){this.attendance=attendance;}
    @Scheduled(cron="0 */15 * * * *",zone="Asia/Shanghai")
    public void syncToday() {
        LocalDate today = LocalDate.now(AttendanceService.ZONE);
        int succeeded = 0, failed = 0;
        try {
            var members = attendance.enabledMembers();
            for (String member : members) {
                try {
                    attendance.sync(member, today, today);
                    attendance.backfillNextPeriod(member, today);
                    succeeded++;
                } catch (RuntimeException ex) {
                    failed++;
                    // 具体原因由连接状态展示；日志不包含账号、远端响应或凭据。
                    log.warn("Moka attendance scheduled sync failed: {}", ex.getClass().getSimpleName());
                }
            }
            log.info("Moka attendance scheduled sync finished: eligible={}, succeeded={}, failed={}",
                    members.size(), succeeded, failed);
        } catch (RuntimeException ex) {
            log.warn("Moka attendance scheduled scan failed; retry next cycle: {}", ex.getClass().getSimpleName());
        }
    }
}
