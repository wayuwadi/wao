package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.AccountEntity;
import cn.wayuwa.wao.domain.AccountIdentityEntity;
import cn.wayuwa.wao.domain.HolidayReminderDeliveryEntity;
import cn.wayuwa.wao.domain.HolidayReminderEntity;
import cn.wayuwa.wao.dto.SaveHolidayReminderRequest;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.mapper.AccountIdentityMapper;
import cn.wayuwa.wao.mapper.AccountMapper;
import cn.wayuwa.wao.mapper.HolidayReminderDeliveryMapper;
import cn.wayuwa.wao.mapper.HolidayReminderMapper;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HolidayCalendarServiceTest {
    private final MemberPreferenceMapper preferences = mock(MemberPreferenceMapper.class);
    private final HolidayReminderMapper reminders = mock(HolidayReminderMapper.class);
    private final HolidayReminderDeliveryMapper deliveries = mock(HolidayReminderDeliveryMapper.class);
    private final AccountMapper accounts = mock(AccountMapper.class);
    private final AccountIdentityMapper identities = mock(AccountIdentityMapper.class);
    private final SettingService settings = mock(SettingService.class);
    private final FeishuOpenApiClient feishu = mock(FeishuOpenApiClient.class);
    private final HolidayCalendarService service = new HolidayCalendarService(
            new HolidayService(), preferences, reminders, deliveries, accounts, identities,
            settings, feishu, new ObjectMapper());

    @BeforeEach
    void availableChannel() {
        AccountEntity account = new AccountEntity();
        account.setId("a1");
        AccountIdentityEntity identity = new AccountIdentityEntity();
        identity.setProviderSubject("ou_test");
        when(accounts.findByMemberId("wg")).thenReturn(account);
        when(identities.findByAccountProvider("a1", "feishu")).thenReturn(identity);
        when(settings.get(SettingService.CHAT_SYNC_APP_ID, "")).thenReturn("app-id");
        when(settings.get(SettingService.CHAT_SYNC_APP_SECRET, "")).thenReturn("secret");
    }

    @Test
    void createsMemberReminderOnlyForSelectedInternationalCategory() {
        when(preferences.find("wg", "holiday-international-categories")).thenReturn("[\"FAMILY\"]");
        when(reminders.find("wg", "international.mothers-day")).thenReturn(null);

        var saved = service.saveReminder("wg", "international.mothers-day",
                new SaveHolidayReminderRequest(true, LocalTime.of(8, 30)));

        assertThat(saved.enabled()).isTrue();
        assertThat(saved.reminderTime()).isEqualTo("08:30");
        ArgumentCaptor<HolidayReminderEntity> inserted = ArgumentCaptor.forClass(HolidayReminderEntity.class);
        verify(reminders).insert(inserted.capture());
        assertThat(inserted.getValue().getMemberId()).isEqualTo("wg");
        assertThat(inserted.getValue().getHolidayName()).isEqualTo("母亲节");
    }

    @Test
    void rejectsUnselectedInternationalCategoryAndUnavailableChannel() {
        when(preferences.find("wg", "holiday-international-categories")).thenReturn("[]");
        assertThatThrownBy(() -> service.saveReminder("wg", "international.mothers-day",
                new SaveHolidayReminderRequest(true, LocalTime.of(9, 0))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("先在节日设置中订阅");

        when(accounts.findByMemberId("wg")).thenReturn(null);
        assertThatThrownBy(() -> service.saveReminder("wg", "china.spring-festival",
                new SaveHolidayReminderRequest(true, LocalTime.of(9, 0))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("尚未绑定飞书");
    }

    @Test
    void modifiesAndClosesExistingReminderWithoutLosingSavedTime() {
        when(preferences.find("wg", "holiday-international-categories")).thenReturn("[\"FAMILY\"]");
        HolidayReminderEntity existing = new HolidayReminderEntity();
        existing.setId("hr1");
        existing.setMemberId("wg");
        existing.setHolidayCode("international.mothers-day");
        existing.setHolidayName("母亲节");
        existing.setEnabled(true);
        existing.setReminderTime(LocalTime.of(8, 30));
        when(reminders.find("wg", "international.mothers-day")).thenReturn(existing);

        var modified = service.saveReminder("wg", "international.mothers-day",
                new SaveHolidayReminderRequest(true, LocalTime.of(9, 15)));
        var closed = service.saveReminder("wg", "international.mothers-day",
                new SaveHolidayReminderRequest(false, null));

        assertThat(modified.reminderTime()).isEqualTo("09:15");
        assertThat(closed.enabled()).isFalse();
        assertThat(closed.reminderTime()).isEqualTo("09:15");
        verify(reminders, org.mockito.Mockito.times(2)).updateById(existing);
    }

    @Test
    void sendsMothersDayDirectMessageOnceAndPersistsDelivery() {
        HolidayReminderEntity reminder = new HolidayReminderEntity();
        reminder.setId("hr1");
        reminder.setMemberId("wg");
        reminder.setHolidayCode("international.mothers-day");
        reminder.setHolidayName("母亲节");
        reminder.setEnabled(true);
        reminder.setReminderTime(LocalTime.of(8, 30));
        when(reminders.selectList(any())).thenReturn(List.of(reminder));
        when(deliveries.selectOne(any())).thenReturn(null);
        when(feishu.tenantAccessToken("app-id", "secret")).thenReturn("token");

        service.sendDueReminders(LocalDateTime.of(2026, 5, 10, 8, 30));

        verify(feishu).sendDirectCard(eq("token"), eq("ou_test"), eq("节日提醒 · 母亲节"),
                any(), eq("https://wao.wayuwa.cn/calendar"), eq("打开 WAO 日历"));
        ArgumentCaptor<HolidayReminderDeliveryEntity> updated =
                ArgumentCaptor.forClass(HolidayReminderDeliveryEntity.class);
        verify(deliveries).updateById(updated.capture());
        assertThat(updated.getValue().getStatus()).isEqualTo("SENT");
        assertThat(updated.getValue().getAttempts()).isEqualTo(1);
    }
}
