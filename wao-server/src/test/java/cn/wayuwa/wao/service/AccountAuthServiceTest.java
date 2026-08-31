package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.auth.AuthProperties;
import cn.wayuwa.wao.auth.ExternalIdentityProfile;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.domain.AccountEntity;
import cn.wayuwa.wao.domain.AccountIdentityEntity;
import cn.wayuwa.wao.domain.AccountLoginEventEntity;
import cn.wayuwa.wao.domain.MemberEntity;
import cn.wayuwa.wao.domain.PasswordCredentialEntity;
import cn.wayuwa.wao.mapper.AccountIdentityMapper;
import cn.wayuwa.wao.mapper.AccountLoginEventMapper;
import cn.wayuwa.wao.mapper.AccountMapper;
import cn.wayuwa.wao.mapper.MemberMapper;
import cn.wayuwa.wao.mapper.PasswordCredentialMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

class AccountAuthServiceTest {
    private final AccountMapper accounts = mock(AccountMapper.class);
    private final AccountIdentityMapper identities = mock(AccountIdentityMapper.class);
    private final AccountLoginEventMapper loginEvents = mock(AccountLoginEventMapper.class);
    private final PasswordCredentialMapper credentials = mock(PasswordCredentialMapper.class);
    private final MemberMapper members = mock(MemberMapper.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final AccountAuthService service = new AccountAuthService(
            accounts, identities, loginEvents, credentials, members, passwords, new AuthProperties(true, true));

    @Test
    void existingFeishuIdentityAlwaysReturnsItsOwnMember() {
        AccountIdentityEntity identity = identity("feishu", "ou_wage", "acc-wg");
        AccountEntity account = account("acc-wg", "wg");
        when(identities.findByProviderSubject("feishu", "ou_wage")).thenReturn(identity);
        when(accounts.selectById("acc-wg")).thenReturn(account);
        when(members.selectById("wg")).thenReturn(member("wg", "娃哥"));
        when(identities.findByAccountId("acc-wg")).thenReturn(List.of(identity));

        var result = service.resolveExternal(
                new ExternalIdentityProfile("feishu", "ou_wage", null, "同名也不影响", null), null, false);

        assertThat(result.session().accountId()).isEqualTo("acc-wg");
        assertThat(result.session().member().id()).isEqualTo("wg");
        assertThat(result.pending()).isNull();
        verify(loginEvents).insert(org.mockito.ArgumentMatchers.<AccountLoginEventEntity>argThat(event ->
                "acc-wg".equals(event.getAccountId()) && "wg".equals(event.getMemberId())
                        && "feishu".equals(event.getProvider())));
    }

    @Test
    void resolvesAnotherFeishuAppsOpenIdThroughUnionId() {
        AccountIdentityEntity identity = identity("feishu", "ou_login_app", "acc-wg");
        identity.setProviderUnionSubject("on_same_person");
        when(identities.findByProviderUnionSubject("feishu", "on_same_person")).thenReturn(identity);
        when(accounts.selectById("acc-wg")).thenReturn(account("acc-wg", "wg"));
        when(members.selectById("wg")).thenReturn(member("wg", "娃哥"));
        when(identities.findByAccountId("acc-wg")).thenReturn(List.of(identity));

        var result = service.resolveExternal(new ExternalIdentityProfile(
                "feishu", "ou_other_app", null, "娃哥", null, "on_same_person"), null, false);

        assertThat(result.session().member().id()).isEqualTo("wg");
        verify(identities).updateById(identity);
    }

    @Test
    void resolvesOfficialAccountOpenIdThroughLegacyWechatUnionSubject() {
        AccountIdentityEntity identity = identity("wechat", "union-same-person", "acc-wg");
        when(identities.findByProviderSubject("wechat", "union-same-person")).thenReturn(identity);
        when(accounts.selectById("acc-wg")).thenReturn(account("acc-wg", "wg"));
        when(members.selectById("wg")).thenReturn(member("wg", "娃哥"));
        when(identities.findByAccountId("acc-wg")).thenReturn(List.of(identity));

        var result = service.resolveExternal(new ExternalIdentityProfile(
                "wechat", "official:wx-app:openid-1", null, "娃哥", null, "union-same-person"), null, false);

        assertThat(result.session().member().id()).isEqualTo("wg");
        assertThat(identity.getProviderUnionSubject()).isEqualTo("union-same-person");
    }

    @Test
    void successfulFeishuLoginBackfillsUnionIdOnExistingIdentity() {
        AccountIdentityEntity identity = identity("feishu", "ou_login_app", "acc-wg");
        when(identities.findByProviderSubject("feishu", "ou_login_app")).thenReturn(identity);
        when(accounts.selectById("acc-wg")).thenReturn(account("acc-wg", "wg"));
        when(members.selectById("wg")).thenReturn(member("wg", "娃哥"));
        when(identities.findByAccountId("acc-wg")).thenReturn(List.of(identity));

        service.resolveExternal(new ExternalIdentityProfile(
                "feishu", "ou_login_app", null, "娃哥", null, "on_backfilled"), null, false);

        assertThat(identity.getProviderUnionSubject()).isEqualTo("on_backfilled");
        verify(identities).updateById(identity);
    }

    @Test
    void refusesToLinkIdentityAlreadyOwnedByAnotherAccount() {
        when(identities.findByProviderSubject("wechat", "union-1"))
                .thenReturn(identity("wechat", "union-1", "acc-wd"));

        assertThatThrownBy(() -> service.resolveExternal(
                new ExternalIdentityProfile("wechat", "union-1", null, "娃弟", null), "acc-wg", true))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("另一个 WAO 账号");
    }

    @Test
    void linkingASecondIdentityDoesNotCountAsAnotherLogin() {
        AccountEntity account = account("acc-wg", "wg");
        when(accounts.selectById("acc-wg")).thenReturn(account);
        when(members.selectById("wg")).thenReturn(member("wg", "娃哥"));
        when(identities.findByAccountId("acc-wg")).thenReturn(List.of(identity("email", "wg@example.com", "acc-wg")));

        service.resolveExternal(new ExternalIdentityProfile(
                "wechat", "wechat-wg", null, "娃哥", null), "acc-wg", true);

        verify(loginEvents, org.mockito.Mockito.never())
                .insert(org.mockito.ArgumentMatchers.<AccountLoginEventEntity>any());
    }

    @Test
    void fifthWrongPasswordLocksCredential() {
        AccountIdentityEntity identity = identity("email", "wg@example.com", "acc-wg");
        PasswordCredentialEntity credential = new PasswordCredentialEntity();
        credential.setAccountId("acc-wg");
        credential.setPasswordHash("hash");
        credential.setFailedAttempts(4);
        when(identities.findByProviderSubject("email", "wg@example.com")).thenReturn(identity);
        when(accounts.selectById("acc-wg")).thenReturn(account("acc-wg", "wg"));
        when(credentials.selectById("acc-wg")).thenReturn(credential);
        when(passwords.matches("wrong-password", "hash")).thenReturn(false);

        assertThatThrownBy(() -> service.login("WG@example.com", "wrong-password"))
                .isInstanceOf(UnauthorizedException.class);

        ArgumentCaptor<PasswordCredentialEntity> captor = ArgumentCaptor.forClass(PasswordCredentialEntity.class);
        verify(credentials).updateById(captor.capture());
        assertThat(captor.getValue().getFailedAttempts()).isEqualTo(5);
        assertThat(captor.getValue().getLockedUntil()).isNotNull();
    }

    @Test
    void profileUpdatePersistsTheRealMemberRecord() {
        AccountEntity account = account("acc-wg", "wg");
        MemberEntity member = member("wg", "娃哥");
        when(accounts.selectById("acc-wg")).thenReturn(account);
        when(members.selectById("wg")).thenReturn(member);
        when(identities.findByAccountId("acc-wg")).thenReturn(List.of());

        var result = service.updateProfile("acc-wg", "真实姓名", "真");

        verify(members).updateById(member);
        assertThat(result.member().name()).isEqualTo("真实姓名");
        assertThat(result.member().initial()).isEqualTo("真");
    }

    @Test
    void superAdminCanGrantAdminRoleButAdminCannot() {
        MemberEntity superAdmin = member("wg", "娃哥");
        superAdmin.setRole("超级管理员");
        MemberEntity target = member("m1", "成员");
        target.setRole("成员");
        when(members.selectById("wg")).thenReturn(superAdmin);
        when(members.selectById("m1")).thenReturn(target);

        var result = service.updateMemberRole("wg", "m1", "管理员");

        verify(members).updateById(target);
        assertThat(result.role()).isEqualTo("管理员");
        assertThatThrownBy(() -> service.updateMemberRole("m1", "m1", "成员"))
                .isInstanceOf(UnauthorizedException.class);
    }

    private static AccountEntity account(String id, String memberId) {
        AccountEntity account = new AccountEntity();
        account.setId(id);
        account.setMemberId(memberId);
        account.setStatus("ACTIVE");
        return account;
    }

    private static AccountIdentityEntity identity(String provider, String subject, String accountId) {
        AccountIdentityEntity identity = new AccountIdentityEntity();
        identity.setId(provider + "-id");
        identity.setProvider(provider);
        identity.setProviderSubject(subject);
        identity.setAccountId(accountId);
        identity.setDisplayName(subject);
        return identity;
    }

    private static MemberEntity member(String id, String name) {
        MemberEntity member = new MemberEntity();
        member.setId(id);
        member.setName(name);
        member.setInitial(name.substring(0, 1));
        member.setColor("#4358D6");
        member.setRole("管理员");
        return member;
    }
}
