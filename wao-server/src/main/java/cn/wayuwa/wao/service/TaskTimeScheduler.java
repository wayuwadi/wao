package cn.wayuwa.wao.service;

import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class TaskTimeScheduler {
    private final TaskTimeService time;
    public TaskTimeScheduler(TaskTimeService time){this.time=time;}
    public void run(){
        try{for(String id:time.pendingIds())process(id);}catch(Exception e){LoggerFactory.getLogger(getClass()).warn("任务时间规则读取失败，将重试",e);}
    }
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void completed(TaskCompletedEvent event){process(event.taskId());}
    private void process(String id){try{time.process(id);}catch(Exception e){try{time.markFailure(id);}catch(Exception ignored){}LoggerFactory.getLogger(getClass()).warn("任务时间规则处理失败，将重试：{}",id,e);}}
}
