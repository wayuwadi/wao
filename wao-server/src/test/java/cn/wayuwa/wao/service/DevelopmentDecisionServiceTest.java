package cn.wayuwa.wao.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import cn.wayuwa.wao.domain.AccountEntity;
import cn.wayuwa.wao.domain.AccountIdentityEntity;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.mapper.AccountIdentityMapper;
import cn.wayuwa.wao.mapper.AccountMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
class DevelopmentDecisionServiceTest {
    final DevelopmentService runs=mock(DevelopmentService.class);
    final AccountMapper accounts=mock(AccountMapper.class);
    final AccountIdentityMapper identities=mock(AccountIdentityMapper.class);
    final ObjectMapper json=new ObjectMapper();
    final DevelopmentDecisionService service=new DevelopmentDecisionService(runs,mock(SettingService.class),accounts,identities,mock(FeishuOpenApiClient.class),json);
    @BeforeEach void identity() {
        var identity=new AccountIdentityEntity();identity.setAccountId("account");
        var account=new AccountEntity();account.setMemberId("owner");account.setStatus("ACTIVE");
        when(identities.findByProviderSubject("feishu","open")).thenReturn(identity);
        when(accounts.selectById("account")).thenReturn(account);
        when(runs.decisionMessage(null,"message","app","open")).thenReturn(Map.of("member_id","owner","id","d1","revision",5L));
    }
    com.fasterxml.jackson.databind.JsonNode reply(String text,String parent) throws Exception {
        var event=json.createObjectNode();event.putObject("sender").put("sender_type","user").putObject("sender_id").put("open_id","open");
        event.putObject("message").put("chat_type","p2p").put("message_type","text").put("parent_id",parent)
            .put("content",json.createObjectNode().put("text",text).toString());return event;
    }
    @Test void ambiguousApprovalAndUnrelatedMessagesNeverContinue() throws Exception {
        for(String text:new String[]{"同意","确认但先不要改","确认是否可行？","确认：改成另一个方案"})service.handle("im.message.receive_v1",reply(text,"message"),"app");
        service.handle("im.message.receive_v1",reply("确认","another-message"),"app");
        verify(runs,never()).decide(anyString(),anyString(),anyLong(),anyString(),anyString());
    }
    @Test void exactReplyToExactMessageContinuesAndRejectionKeepsReason() throws Exception {
        service.handle("im.message.receive_v1",reply("确认","message"),"app");
        verify(runs).decide("owner","d1",5L,"approve","确认");
        service.handle("im.message.receive_v1",reply("退回：先调整目标","message"),"app");
        verify(runs).decide("owner","d1",5L,"reject","退回：先调整目标");
    }
    @Test void DisabledOrChangedBindingCannotApprove() throws Exception {
        var account=new AccountEntity();account.setMemberId("other");account.setStatus("ACTIVE");when(accounts.selectById("account")).thenReturn(account);
        assertThatThrownBy(()->service.handle("im.message.receive_v1",reply("确认","message"),"app")).hasMessageContaining("本人");
        verify(runs,never()).decide(anyString(),anyString(),anyLong(),anyString(),anyString());
    }
}
