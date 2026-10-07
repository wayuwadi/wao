package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.mapper.AccountIdentityMapper;
import cn.wayuwa.wao.mapper.AccountMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 仅处理已鉴权飞书事件中的开发决定；普通群收集和业务 AI 权限保持原边界。 */
@Service
public class DevelopmentDecisionService {
    private final DevelopmentService runs;
    private final SettingService settings;
    private final AccountMapper accounts;
    private final AccountIdentityMapper identities;
    private final FeishuOpenApiClient feishu;
    private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Value("${wao.development.notifications-enabled:true}")
    private boolean notificationsEnabled=true;
    public DevelopmentDecisionService(DevelopmentService runs, SettingService settings, AccountMapper accounts,
            AccountIdentityMapper identities, FeishuOpenApiClient feishu, ObjectMapper json) {
        this.runs=runs;this.settings=settings;this.accounts=accounts;this.identities=identities;this.feishu=feishu;this.json=json;
    }
    /** 通过既有加密配置投递本人决定卡；失败保留可见状态并有限重试。 */
    @Scheduled(fixedDelay=30000, initialDelay=30000)
    public void deliver() {
        if(!notificationsEnabled)return;
        for(var row:runs.pendingDecisions()) {
            String id=row.get("id").toString();
            try {
                String app=settings.get(SettingService.CHAT_SYNC_APP_ID, "");
                String secret=settings.get(SettingService.CHAT_SYNC_APP_SECRET, "");
                var account=accounts.findByMemberId(row.get("member_id").toString());
                var identity=account==null?null:identities.findByAccountProvider(account.getId(),"feishu");
                if(app==null||app.isBlank()||secret==null||secret.isBlank()||identity==null
                        ||identity.getProviderSubject()==null||identity.getProviderSubject().isBlank()) {
                    runs.deliveryFailed(id); continue;
                }
                String open=identity.getProviderSubject();
                if(!runs.delivering(id,app,open)) continue;
                String token=feishu.tenantAccessToken(app,secret);
                String message=feishu.sendDevelopmentDecision(token,open,id,((Number)row.get("revision")).longValue(),
                        row.get("question").toString(),row.get("reason").toString());
                runs.delivered(id,message);
            } catch(RuntimeException ignored) {runs.deliveryFailed(id);}
        }
    }
    /** 已认证回调专用路由：精确消息、应用、发送人和版本缺一不可。返回 null 继续原收集流程。 */
    public Map<String,Object> handle(String type, JsonNode event, String app) {
        if("card.action.trigger".equals(type)) {
            JsonNode value=event.path("action").path("value");
            if(!"wao-development-decision".equals(value.path("kind").asText())) return null;
            String open=event.path("operator").path("open_id").asText("");
            String message=event.path("context").path("open_message_id").asText("");
            var decision=runs.decisionMessage(value.path("decisionId").asText(),message,app,open);
            requireDecision(decision,open);
            String action=value.path("action").asText();
            runs.decide(decision.get("member_id").toString(),decision.get("id").toString(),value.path("revision").asLong(-1),
                    action,"approve".equals(action)?"飞书明确确认：按此方案继续":"飞书明确退回：重新规划");
            return Map.of("toast",Map.of("type","success","content","决定已保存；仅对应任务进入续跑或退回状态"));
        }
        if(!"im.message.receive_v1".equals(type)) return null;
        JsonNode message=event.path("message");
        if(!"p2p".equals(message.path("chat_type").asText())||!"text".equals(message.path("message_type").asText())
                ||!"user".equals(event.path("sender").path("sender_type").asText())) return null;
        String parent=message.path("parent_id").asText(message.path("root_id").asText(""));
        if(parent.isBlank()) return null;
        String open=event.path("sender").path("sender_id").path("open_id").asText("");
        var decision=runs.decisionMessage(null,parent,app,open);
        if(decision==null || decision.isEmpty()) return null;
        requireDecision(decision,open);
        String text;
        try {text=json.readTree(message.path("content").asText()).path("text").asText().trim();}
        catch(Exception ignored){throw new BadRequestException("飞书决定回复无法读取");}
        String action;
        if(text.equals("确认") || text.equals("确认继续")) action="approve";
        else if(text.equals("退回") || text.startsWith("退回：") || text.startsWith("退回:")) action="reject";
        else return Map.of(); // 非明确指令不能启动执行。
        runs.decide(decision.get("member_id").toString(),decision.get("id").toString(),((Number)decision.get("revision")).longValue(),action,text);
        return Map.of();
    }
    private void requireDecision(Map<String,Object> decision, String open) {
        if(decision==null||decision.get("member_id")==null||open.isBlank())throw new BadRequestException("决定消息或批准人不匹配");
        var identity=identities.findByProviderSubject("feishu",open);
        var account=identity==null?null:accounts.selectById(identity.getAccountId());
        if(account==null||!"ACTIVE".equals(account.getStatus())||!decision.get("member_id").equals(account.getMemberId()))
            throw new BadRequestException("只有绑定的本人可以回复开发决定");
    }
}
