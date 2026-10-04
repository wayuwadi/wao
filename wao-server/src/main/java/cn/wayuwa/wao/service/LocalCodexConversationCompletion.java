package cn.wayuwa.wao.service;

import cn.wayuwa.wao.domain.AiAssistantRunEntity;
import cn.wayuwa.wao.mapper.AiAssistantRunMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 本机会话结果与执行终态原子提交，失效 claim 不再追加回复。 */
@Service
public class LocalCodexConversationCompletion {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<TianrenVisualService> tianrenVisuals;
    private final AiAssistantRunMapper runs;
    private final AiAssistantConversationService conversations;
    public LocalCodexConversationCompletion(AiAssistantRunMapper runs, AiAssistantConversationService conversations) {
        this.runs = runs; this.conversations = conversations;
    }
    /** 成功终态和成员私有回复同时提交；失败回滚后仍走既有可重试失败收口。 */
    @Transactional
    public int complete(AiAssistantRunEntity run, String claim, String response) {
        if ("TIANREN".equals(run.getUsagePurpose()) && tianrenVisuals != null
                && tianrenVisuals.getObject().supports(run.getId()))
            return tianrenVisuals.getObject().completeText(run, claim, response);
        int updated = runs.completeProviderSucceeded(run.getId(), claim, response);
        if (updated == 1) conversations.appendAssistantReply(run, response);
        return updated;
    }
}
