package cn.wayuwa.wao.service;

import org.springframework.stereotype.Component;

/** 既有 Automation Cron 到期时，仅持久化入队；详细执行记录由同步中心管理。 */
public class WeKnoraSyncJobHandler implements JobHandler {
    private final WeKnoraSyncService sync;
    public WeKnoraSyncJobHandler(WeKnoraSyncService sync){this.sync=sync;}
    @Override public String name(){return WeKnoraSyncService.HANDLER;}
    /** 入队后异步执行，不能把该文案解释为数据已同步完成。 */
    @Override public String execute(){return "已入队 · "+sync.enqueue("manual",null);}
    /** 区分自动化页面的手动触发与 Cron 计划触发。 */
    @Override public String execute(cn.wayuwa.wao.domain.AutomationJobEntity job,java.time.LocalDateTime fireAt){
        return "已入队 · "+sync.enqueue(fireAt==null?"manual":"scheduled",null);
    }
}
