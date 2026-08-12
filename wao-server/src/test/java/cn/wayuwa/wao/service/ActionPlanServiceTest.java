package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.domain.ActionPlanEntity;
import cn.wayuwa.wao.domain.ActionPlanItemEntity;
import cn.wayuwa.wao.domain.ActionReminderDeliveryEntity;
import cn.wayuwa.wao.dto.ActionPlanItemInput;
import cn.wayuwa.wao.dto.SaveActionPlanRequest;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.mapper.AccountIdentityMapper;
import cn.wayuwa.wao.mapper.AccountMapper;
import cn.wayuwa.wao.mapper.ActionCheckinMapper;
import cn.wayuwa.wao.mapper.ActionDayNoteMapper;
import cn.wayuwa.wao.mapper.ActionPlanItemMapper;
import cn.wayuwa.wao.mapper.ActionPlanMapper;
import cn.wayuwa.wao.mapper.ActionReminderDeliveryMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ActionPlanServiceTest {
    private final ActionPlanMapper plans = mock(ActionPlanMapper.class);
    private final ActionPlanItemMapper items = mock(ActionPlanItemMapper.class);
    private final ActionCheckinMapper checkins = mock(ActionCheckinMapper.class);
    private final ActionDayNoteMapper notes = mock(ActionDayNoteMapper.class);
    private final ActionReminderDeliveryMapper reminders = mock(ActionReminderDeliveryMapper.class);
    private final AccountMapper accounts = mock(AccountMapper.class);
    private final AccountIdentityMapper identities = mock(AccountIdentityMapper.class);
    private final SettingService settings = mock(SettingService.class);
    private final FeishuOpenApiClient feishu = mock(FeishuOpenApiClient.class);
    private final ActionPlanService service = new ActionPlanService(plans, items, checkins, notes, reminders,
            accounts, identities, settings, feishu, new ObjectMapper());

    @Test
    void createsMemberPrivatePlanFromUserDefinedActions() {
        List<ActionPlanItemEntity> storedItems = new ArrayList<>();
        doAnswer(invocation -> { storedItems.add(invocation.getArgument(0)); return 1; })
                .when(items).insert(any(ActionPlanItemEntity.class));
        when(items.selectList(any())).thenAnswer(invocation -> new ArrayList<>(storedItems));
        when(checkins.selectList(any())).thenReturn(List.of());

        var created = service.create("wd", new SaveActionPlanRequest(
                "100 天健康重启", "用可持续行动改善状态", LocalDate.now(), LocalDate.now().plusDays(99),
                LocalTime.of(20, 0), true,
                List.of(new ActionPlanItemInput(null, "完成自己确认的今日训练", List.of(1, 3, 5)))));

        ArgumentCaptor<ActionPlanEntity> plan = ArgumentCaptor.forClass(ActionPlanEntity.class);
        verify(plans).insert(plan.capture());
        assertThat(plan.getValue().getMemberId()).isEqualTo("wd");
        assertThat(plan.getValue().getStatus()).isEqualTo("ACTIVE");
        assertThat(created.title()).isEqualTo("100 天健康重启");
        assertThat(created.items()).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("完成自己确认的今日训练");
            assertThat(item.weekdays()).containsExactly(1, 3, 5);
        });
    }

    @Test
    void recordsExplicitFailureInsteadOfPretendingReminderWasSent() {
        LocalDateTime due = LocalDateTime.of(2026, 8, 12, 20, 0);
        ActionPlanEntity plan = plan(due);
        ActionPlanItemEntity item = item(plan.getId(), due.toLocalDate());
        when(plans.selectList(any())).thenReturn(List.of(plan));
        when(items.selectList(any())).thenReturn(List.of(item));
        when(reminders.selectOne(any())).thenReturn(null);
        when(accounts.findByMemberId("wd")).thenReturn(null);

        service.sendDueReminders(due);

        ArgumentCaptor<ActionReminderDeliveryEntity> inserted = ArgumentCaptor.forClass(ActionReminderDeliveryEntity.class);
        ArgumentCaptor<ActionReminderDeliveryEntity> updated = ArgumentCaptor.forClass(ActionReminderDeliveryEntity.class);
        verify(reminders).insert(inserted.capture());
        verify(reminders).updateById(updated.capture());
        assertThat(inserted.getValue()).isSameAs(updated.getValue());
        assertThat(updated.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(updated.getValue().getErrorMessage()).contains("尚未绑定飞书");
        assertThat(updated.getValue().getSentAt()).isNull();
    }

    @Test
    void claimsExistingFailedDeliveryBeforeRetrying() {
        LocalDateTime due = LocalDateTime.of(2026, 8, 12, 20, 10);
        ActionPlanEntity plan = plan(due);
        ActionPlanItemEntity item = item(plan.getId(), due.toLocalDate());
        ActionReminderDeliveryEntity failed = new ActionReminderDeliveryEntity();
        failed.setId("ar1"); failed.setPlanId(plan.getId()); failed.setReminderDate(due.toLocalDate());
        failed.setStatus("FAILED"); failed.setAttempts(1); failed.setAttemptedAt(due.minusMinutes(10));
        when(plans.selectList(any())).thenReturn(List.of(plan));
        when(items.selectList(any())).thenReturn(List.of(item));
        when(reminders.selectOne(any())).thenReturn(failed);
        when(reminders.claimRetry("ar1", due.minusMinutes(10), due, 2)).thenReturn(0);

        service.sendDueReminders(due);

        verify(reminders).claimRetry("ar1", due.minusMinutes(10), due, 2);
        org.mockito.Mockito.verifyNoInteractions(accounts, identities, settings, feishu);
    }

    @Test
    void staleProcessingDeliveryCanBeClaimedAfterNodeStopsMidSend() {
        LocalDateTime due = LocalDateTime.of(2026, 8, 12, 20, 10);
        ActionPlanEntity plan = plan(due);
        ActionPlanItemEntity item = item(plan.getId(), due.toLocalDate());
        ActionReminderDeliveryEntity processing = new ActionReminderDeliveryEntity();
        processing.setId("ar1"); processing.setPlanId(plan.getId()); processing.setReminderDate(due.toLocalDate());
        processing.setStatus("PROCESSING"); processing.setAttempts(1); processing.setAttemptedAt(due.minusMinutes(10));
        when(plans.selectList(any())).thenReturn(List.of(plan));
        when(items.selectList(any())).thenReturn(List.of(item));
        when(reminders.selectOne(any())).thenReturn(processing);
        when(reminders.claimRetry("ar1", due.minusMinutes(10), due, 2)).thenReturn(0);

        service.sendDueReminders(due);

        verify(reminders).claimRetry("ar1", due.minusMinutes(10), due, 2);
        org.mockito.Mockito.verifyNoInteractions(accounts, identities, settings, feishu);
    }

    private ActionPlanEntity plan(LocalDateTime due) {
        ActionPlanEntity plan = new ActionPlanEntity();
        plan.setId("ap1"); plan.setMemberId("wd"); plan.setTitle("健康重启"); plan.setVision("");
        plan.setStartDate(due.toLocalDate().minusDays(1)); plan.setTargetDate(due.toLocalDate().plusDays(10));
        plan.setReminderTime(due.toLocalTime()); plan.setReminderEnabled(true); plan.setStatus("ACTIVE");
        return plan;
    }

    private ActionPlanItemEntity item(String planId, LocalDate date) {
        ActionPlanItemEntity item = new ActionPlanItemEntity();
        item.setId("ai1"); item.setPlanId(planId); item.setTitle("今日行动");
        item.setWeekdaysJson("[" + date.getDayOfWeek().getValue() + "]"); item.setActive(true);
        item.setActiveFrom(date.minusDays(1)); item.setOrd(0);
        return item;
    }
}
