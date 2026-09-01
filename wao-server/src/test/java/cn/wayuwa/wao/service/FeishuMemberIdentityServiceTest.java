package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.domain.AccountEntity;
import cn.wayuwa.wao.domain.AccountIdentityEntity;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.mapper.AccountIdentityMapper;
import cn.wayuwa.wao.mapper.AccountMapper;
import org.junit.jupiter.api.Test;

class FeishuMemberIdentityServiceTest {
    private final AccountIdentityMapper identities = mock(AccountIdentityMapper.class);
    private final AccountMapper accounts = mock(AccountMapper.class);
    private final SettingService settings = mock(SettingService.class);
    private final FeishuOpenApiClient feishu = mock(FeishuOpenApiClient.class);
    private final FeishuMemberIdentityService service =
            new FeishuMemberIdentityService(identities, accounts, settings, feishu);

    @Test
    void directOpenIdBindingWinsWithoutCallingFeishuAgain() {
        when(identities.findByProviderSubject("feishu", "ou-sender"))
                .thenReturn(identity("account-1"));
        when(accounts.selectById("account-1")).thenReturn(account("member-1"));

        assertThat(service.resolvePolledMember("oc-chat", "ou-sender")).isEqualTo("member-1");
        verify(feishu, never()).tenantAccessToken("app", "secret");
    }

    @Test
    void fallsBackToUnionIdButNeverToAnAdministrator() {
        when(settings.get(SettingService.CHAT_SYNC_APP_ID, "")).thenReturn("app");
        when(settings.get(SettingService.CHAT_SYNC_APP_SECRET, "")).thenReturn("secret");
        when(feishu.tenantAccessToken("app", "secret")).thenReturn("token");
        when(feishu.chatMemberUnionId("token", "oc-chat", "ou-sender")).thenReturn("on-union");
        when(identities.findByProviderUnionSubject("feishu", "on-union"))
                .thenReturn(identity("account-2"));
        when(accounts.selectById("account-2")).thenReturn(account("member-2"));

        assertThat(service.resolvePolledMember("oc-chat", "ou-sender")).isEqualTo("member-2");

        when(identities.findByProviderUnionSubject("feishu", "on-union")).thenReturn(null);
        assertThat(service.resolvePolledMember("oc-chat", "ou-sender")).isNull();
    }

    private static AccountIdentityEntity identity(String accountId) {
        AccountIdentityEntity value = new AccountIdentityEntity();
        value.setAccountId(accountId);
        return value;
    }

    private static AccountEntity account(String memberId) {
        AccountEntity value = new AccountEntity();
        value.setMemberId(memberId);
        return value;
    }
}
