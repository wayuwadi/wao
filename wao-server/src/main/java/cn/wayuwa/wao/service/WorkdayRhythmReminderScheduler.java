package cn.wayuwa.wao.service;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
/** 每分钟扫描今日必做的真实后台提醒。 */
@Component public class WorkdayRhythmReminderScheduler {
    private static final Logger log=LoggerFactory.getLogger(WorkdayRhythmReminderScheduler.class);
    private final WorkdayRhythmReminderService service;
    public WorkdayRhythmReminderScheduler(WorkdayRhythmReminderService service){this.service=service;}
    @Scheduled(fixedDelay=60_000, initialDelay=55_000) public void sendDue(){
        try{service.sendDue(LocalDateTime.now(WorkdayRhythmDashboardService.ZONE));}
        catch(Exception exception){log.error("今日必做提醒轮询失败",exception);}
    }
}
