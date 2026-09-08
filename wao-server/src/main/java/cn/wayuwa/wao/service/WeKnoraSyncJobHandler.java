package cn.wayuwa.wao.service;

import org.springframework.stereotype.Component;

/** 既有 Automation Cron 到期时，仅持久化入队；详细执行记录由同步中心管理。 */
@Component
public class WeKnoraSyncJobHandler implements JobHandler {
    private final WeKnoraSyncService sync;
    public WeKnoraSyncJobHandler(WeKnoraSyncService sync){this.sync=sync;}
    @Override public String name(){return WeKnoraSyncService.HANDLER;}
    /** 入队后异步执行，不能把该文案解释为数据已同步完成。 */
    @Override public String execute(){return "已入队 · "+sync.enqueue("scheduled",null);}
}
