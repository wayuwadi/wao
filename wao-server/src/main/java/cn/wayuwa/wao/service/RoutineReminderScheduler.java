package cn.wayuwa.wao.service;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
public class RoutineReminderScheduler {
    private final RoutineService routines;
    public RoutineReminderScheduler(RoutineService routines){this.routines=routines;}
    @Scheduled(fixedDelay=60_000,initialDelay=60_000) public void run(){
        try {for(String id:routines.reminderIds())try{routines.deliverDue(id);}catch(Exception e){LoggerFactory.getLogger(getClass()).warn("个人日期/习惯提醒失败，将重试：{}",id,e);}}
        catch(Exception e){LoggerFactory.getLogger(getClass()).warn("个人提醒读取失败，将重试",e);}
    }
}
