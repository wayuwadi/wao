package cn.wayuwa.wao.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.domain.AccountEntity;
import cn.wayuwa.wao.domain.AccountIdentityEntity;
import cn.wayuwa.wao.domain.WorkdayRhythmChecklistOccurrenceEntity;
import cn.wayuwa.wao.domain.WorkdayRhythmReminderDeliveryEntity;
import cn.wayuwa.wao.feishu.FeishuAppLinks;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.feishu.FeishuWebhookNotifier;
import cn.wayuwa.wao.mapper.AccountIdentityMapper;
import cn.wayuwa.wao.mapper.AccountMapper;
import cn.wayuwa.wao.mapper.WorkdayRhythmChecklistItemMapper;
import cn.wayuwa.wao.mapper.WorkdayRhythmChecklistOccurrenceMapper;
import cn.wayuwa.wao.mapper.WorkdayRhythmReminderDeliveryMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkdayRhythmReminderServiceTest {
    @Test
    void sendsIncompleteDirectReminderOnlyOnceAcrossRepeatedScans() {
        var items = mock(WorkdayRhythmChecklistItemMapper.class);
        var occurrences = mock(WorkdayRhythmChecklistOccurrenceMapper.class);
        var deliveries = mock(WorkdayRhythmReminderDeliveryMapper.class);
        var dashboard = mock(WorkdayRhythmDashboardService.class);
        var accounts = mock(AccountMapper.class);
        var identities = mock(AccountIdentityMapper.class);
        var settings = mock(SettingService.class);
        var feishu = mock(FeishuOpenApiClient.class);
        var links = mock(FeishuAppLinks.class);

        LocalDate date = LocalDate.parse("2026-08-23");
        LocalDateTime now = date.atTime(8, 45);
        WorkdayRhythmChecklistOccurrenceEntity occurrence = new WorkdayRhythmChecklistOccurrenceEntity();
        occurrence.setId("occ1"); occurrence.setMemberId("m1"); occurrence.setOccurrenceDate(date);
        occurrence.setTitle("上班打卡"); occurrence.setDueTime(LocalTime.of(9, 0)); occurrence.setDueNextDay(false);
        occurrence.setReminderTime(LocalTime.of(8, 30)); occurrence.setReminderNextDay(false);
        occurrence.setNotifyDirect(true); occurrence.setNotifyChat(false); occurrence.setNotifyWebhook(false);
        when(items.reminderMembers()).thenReturn(List.of("m1"));
        when(occurrences.selectList(any(Wrapper.class))).thenReturn(List.of(occurrence));

        WorkdayRhythmReminderDeliveryEntity sent = new WorkdayRhythmReminderDeliveryEntity();
        sent.setId("delivery1"); sent.setOccurrenceId("occ1"); sent.setChannel("DIRECT");
        sent.setStatus("SENT"); sent.setAttempts(1); sent.setAttemptedAt(now); sent.setSentAt(now);
        when(deliveries.selectOne(any(Wrapper.class))).thenReturn(null, sent);

        AccountEntity account = new AccountEntity(); account.setId("a1"); account.setMemberId("m1");
        AccountIdentityEntity identity = new AccountIdentityEntity(); identity.setProviderSubject("ou_member");
        when(accounts.findByMemberId("m1")).thenReturn(account);
        when(identities.findByAccountProvider("a1", "feishu")).thenReturn(identity);
        when(settings.get(SettingService.CHAT_SYNC_APP_ID, "")).thenReturn("app");
        when(settings.get(SettingService.CHAT_SYNC_APP_SECRET, "")).thenReturn("secret");
        when(feishu.tenantAccessToken("app", "secret")).thenReturn("token");
        when(links.open("/workday-rhythm")).thenReturn("https://wao.example/workday-rhythm");

        WorkdayRhythmReminderService service = new WorkdayRhythmReminderService(items, occurrences, deliveries,
                dashboard, accounts, identities, settings, feishu, mock(FeishuWebhookConfigService.class),
                mock(FeishuWebhookNotifier.class), links, mock(NotificationSettingsService.class));
        service.sendDue(now);
        service.sendDue(now.plusMinutes(1));

        verify(feishu, times(1)).sendDirectCard(eq("token"), eq("ou_member"), eq("今日必做 · 上班打卡"),
                anyString(), anyString(), anyString());
        verify(deliveries, times(1)).insert(any(WorkdayRhythmReminderDeliveryEntity.class));
    }
}
