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
import cn.wayuwa.wao.domain.FamilyImportantDateDeliveryEntity;
import cn.wayuwa.wao.domain.FamilyImportantDateEntity;
import cn.wayuwa.wao.dto.SaveFamilyImportantDateRequest;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.mapper.AccountIdentityMapper;
import cn.wayuwa.wao.mapper.AccountMapper;
import cn.wayuwa.wao.mapper.FamilyImportantDateDeliveryMapper;
import cn.wayuwa.wao.mapper.FamilyImportantDateMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FamilyImportantDateServiceTest {
    private final FamilyImportantDateMapper dates = mock(FamilyImportantDateMapper.class);
    private final FamilyImportantDateDeliveryMapper deliveries = mock(FamilyImportantDateDeliveryMapper.class);
    private final AccountMapper accounts = mock(AccountMapper.class);
    private final AccountIdentityMapper identities = mock(AccountIdentityMapper.class);
    private final SettingService settings = mock(SettingService.class);
    private final FeishuOpenApiClient feishu = mock(FeishuOpenApiClient.class);
    private final NotificationSettingsService notificationSettings = mock(NotificationSettingsService.class);
    private final FamilyImportantDateService service = new FamilyImportantDateService(
            dates, deliveries, accounts, identities, settings, feishu, notificationSettings);

    @BeforeEach
    void availableChannel() {
        when(notificationSettings.allowsReminders()).thenReturn(true);
        AccountEntity account = new AccountEntity();
        account.setId("a1");
        AccountIdentityEntity identity = new AccountIdentityEntity();
        identity.setProviderSubject("ou_family");
        identity.setProviderUnionSubject("on_family");
        when(accounts.findByMemberId("wg")).thenReturn(account);
        when(identities.findByAccountProvider("a1", "feishu")).thenReturn(identity);
        when(settings.get(SettingService.CHAT_SYNC_APP_ID, "")).thenReturn("app-id");
        when(settings.get(SettingService.CHAT_SYNC_APP_SECRET, "")).thenReturn("secret");
    }

    @Test
    void expandsMemberDateEveryYearAndKeepsLeapDayExact() {
        FamilyImportantDateEntity birthday = date("fid1", LocalDate.of(1990, 5, 10));
        FamilyImportantDateEntity leapDay = date("fid2", LocalDate.of(1992, 2, 29));
        leapDay.setName("闰日纪念日");
        when(dates.selectList(any())).thenReturn(List.of(birthday, leapDay));

        var calendar = service.calendar("wg", LocalDate.of(2025, 1, 1), LocalDate.of(2026, 12, 31));

        assertThat(calendar.items()).hasSize(2);
        assertThat(calendar.events()).extracting(event -> event.date())
                .contains("2025-05-10", "2026-05-10")
                .doesNotContain("2025-02-28", "2026-02-28");
        assertThat(calendar.reminderChannelAvailable()).isTrue();
    }

    @Test
    void savesCalendarOnlyWhenFeishuChannelIsUnavailableButRejectsFakeReminder() {
        when(accounts.findByMemberId("wg")).thenReturn(null);

        var saved = service.create("wg", new SaveFamilyImportantDateRequest(
                " 妈妈生日 ", LocalDate.of(1990, 5, 10), " 喜欢百合花 ", false, 0, null));

        assertThat(saved.name()).isEqualTo("妈妈生日");
        assertThat(saved.note()).isEqualTo("喜欢百合花");
        ArgumentCaptor<FamilyImportantDateEntity> inserted = ArgumentCaptor.forClass(FamilyImportantDateEntity.class);
        verify(dates).insert(inserted.capture());
        assertThat(inserted.getValue().getMemberId()).isEqualTo("wg");

        assertThatThrownBy(() -> service.create("wg", new SaveFamilyImportantDateRequest(
                "爸爸生日", LocalDate.of(1991, 6, 8), null, true, 3, LocalTime.of(8, 30))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("尚未绑定飞书");
    }

    @Test
    void sendsAdvanceReminderOnceWithOccurrenceAsIdempotencyDate() {
        FamilyImportantDateEntity birthday = date("fid1", LocalDate.of(1990, 5, 10));
        birthday.setReminderEnabled(true);
        birthday.setReminderDaysBefore(3);
        birthday.setReminderTime(LocalTime.of(8, 30));
        birthday.setNote("记得提前订花");
        when(dates.selectList(any())).thenReturn(List.of(birthday));
        when(deliveries.selectOne(any())).thenReturn(null);
        when(feishu.tenantAccessToken("app-id", "secret")).thenReturn("token");

        service.sendDueReminders(LocalDateTime.of(2026, 5, 7, 8, 30));

        verify(feishu).sendDirectCard(eq("token"), eq("ou_family"), eq("日历提醒 · 妈妈生日"),
                org.mockito.ArgumentMatchers.contains("3 天后"), eq("https://wao.wayuwa.cn/calendar"),
                eq("打开 WAO 日历"));
        ArgumentCaptor<FamilyImportantDateDeliveryEntity> inserted =
                ArgumentCaptor.forClass(FamilyImportantDateDeliveryEntity.class);
        verify(deliveries).insert(inserted.capture());
        assertThat(inserted.getValue().getOccurrenceDate()).isEqualTo(LocalDate.of(2026, 5, 10));
        ArgumentCaptor<FamilyImportantDateDeliveryEntity> updated =
                ArgumentCaptor.forClass(FamilyImportantDateDeliveryEntity.class);
        verify(deliveries).updateById(updated.capture());
        assertThat(updated.getValue().getStatus()).isEqualTo("SENT");
    }

    @Test
    void convertsBothDirectionsAndExpandsLunarReminderByLunarMonthDay() {
        var conversion = service.fromLunar(2026, 1, 1, false);
        assertThat(conversion.solarDate()).isEqualTo("2026-02-17");
        assertThat(service.fromSolar(LocalDate.of(2026, 2, 17)).lunarLabel()).contains("正月初一");

        FamilyImportantDateEntity lunar = date("fid-lunar", LocalDate.of(2025, 1, 29));
        lunar.setCalendarType("LUNAR");
        lunar.setLunarMonth(1);
        lunar.setLunarDay(1);
        lunar.setLunarLeapMonth(false);
        when(dates.selectList(any())).thenReturn(List.of(lunar));

        var calendar = service.calendar("wg", LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28));

        assertThat(calendar.events()).singleElement()
                .satisfies(event -> {
                    assertThat(event.date()).isEqualTo("2026-02-17");
                    assertThat(event.calendarType()).isEqualTo("LUNAR");
                    assertThat(event.lunarDateLabel()).contains("正月初一");
                });
    }

    @Test
    void sendsGroupReminderAndMentionsOnlyTheCreatorMappedThroughUnionId() {
        FamilyImportantDateEntity reminder = date("fid-group", LocalDate.of(2025, 5, 10));
        reminder.setReminderEnabled(true);
        reminder.setReminderDaysBefore(1);
        reminder.setReminderTime(LocalTime.of(9, 0));
        reminder.setNotificationType("GROUP");
        reminder.setChatId("oc_team");
        reminder.setChatName("家人群");
        when(dates.selectList(any())).thenReturn(List.of(reminder));
        when(deliveries.selectOne(any())).thenReturn(null);
        when(feishu.tenantAccessToken("app-id", "secret")).thenReturn("token");
        when(feishu.chatMemberOpenIdByUnionId("token", "oc_team", "on_family"))
                .thenReturn("ou_group_family");

        service.sendDueReminders(LocalDateTime.of(2026, 5, 9, 9, 0));

        ArgumentCaptor<cn.wayuwa.wao.feishu.FeishuWebhookNotifier.Card> card =
                ArgumentCaptor.forClass(cn.wayuwa.wao.feishu.FeishuWebhookNotifier.Card.class);
        verify(feishu).sendCard(eq("token"), eq("oc_team"), card.capture());
        assertThat(card.getValue().body()).contains("<at id=ou_group_family></at>", "1 天后");
    }

    @Test
    void savesOnlyARealSelectedChatWhoseCreatorCanBeMentioned() {
        when(feishu.tenantAccessToken("app-id", "secret")).thenReturn("token");
        when(feishu.listChats("token")).thenReturn(List.of(
                new FeishuOpenApiClient.FeishuChat("oc_family", "家人群")));
        when(feishu.chatMemberOpenIdByUnionId("token", "oc_family", "on_family"))
                .thenReturn("ou_group_family");

        service.create("wg", new SaveFamilyImportantDateRequest(
                "续费提醒", LocalDate.of(2026, 9, 1), null, true, 1, LocalTime.of(9, 0),
                "SOLAR", null, null, false, "GROUP", "oc_family", "家人群"));

        verify(dates).insert(any(FamilyImportantDateEntity.class));
        assertThatThrownBy(() -> service.create("wg", new SaveFamilyImportantDateRequest(
                "伪造群名", LocalDate.of(2026, 9, 1), null, true, 1, LocalTime.of(9, 0),
                "SOLAR", null, null, false, "GROUP", "oc_family", "另一个群")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("重新选择");
    }

    private static FamilyImportantDateEntity date(String id, LocalDate eventDate) {
        FamilyImportantDateEntity entity = new FamilyImportantDateEntity();
        entity.setId(id);
        entity.setMemberId("wg");
        entity.setName("妈妈生日");
        entity.setEventDate(eventDate);
        entity.setCalendarType("SOLAR");
        entity.setNotificationType("DIRECT");
        entity.setReminderEnabled(false);
        entity.setReminderDaysBefore(0);
        return entity;
    }
}
