package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.domain.WorkdayRhythmChecklistItemEntity;
import cn.wayuwa.wao.domain.WorkdayRhythmChecklistOccurrenceEntity;
import cn.wayuwa.wao.domain.WorkdayRhythmSettingEntity;
import cn.wayuwa.wao.dto.SaveWorkdayRhythmFocusRequest;
import cn.wayuwa.wao.mapper.DocMapper;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import cn.wayuwa.wao.mapper.WorkdayRhythmChecklistItemMapper;
import cn.wayuwa.wao.mapper.WorkdayRhythmChecklistOccurrenceMapper;
import cn.wayuwa.wao.mapper.WorkdayRhythmDayMapper;
import cn.wayuwa.wao.mapper.WorkdayRhythmReminderDeliveryMapper;
import cn.wayuwa.wao.mapper.WorkdayRhythmSettingMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WorkdayRhythmDashboardServiceTest {
    private final WorkdayRhythmSettingMapper settings = mock(WorkdayRhythmSettingMapper.class);
    private final WorkdayRhythmChecklistItemMapper items = mock(WorkdayRhythmChecklistItemMapper.class);
    private final WorkdayRhythmChecklistOccurrenceMapper occurrences = mock(WorkdayRhythmChecklistOccurrenceMapper.class);
    private final WorkdayRhythmReminderDeliveryMapper deliveries = mock(WorkdayRhythmReminderDeliveryMapper.class);
    private final WorkdayRhythmDayMapper days = mock(WorkdayRhythmDayMapper.class);
    private final MemberPreferenceMapper preferences = mock(MemberPreferenceMapper.class);
    private final WorkdayRhythmDashboardService service = new WorkdayRhythmDashboardService(settings, items,
            occurrences, deliveries, days, preferences, mock(DocMapper.class), mock(KnowledgeService.class),
            new ObjectMapper());
    private WorkdayRhythmChecklistItemEntity plan;
    private WorkdayRhythmChecklistOccurrenceEntity planOccurrence;

    @BeforeEach
    void prepare() {
        WorkdayRhythmSettingEntity setting = new WorkdayRhythmSettingEntity();
        setting.setMemberId("m1"); setting.setFollowAlternateWeek(true);
        setting.setDocumentTitlePattern("{date} 每日工作记录");
        setting.setTianrenEnabled(true); setting.setTianrenRequired(false);
        when(settings.selectById("m1")).thenReturn(setting);
        when(items.selectCount(any(Wrapper.class))).thenReturn(1L);

        plan = new WorkdayRhythmChecklistItemEntity(); plan.setId("plan"); plan.setMemberId("m1");
        plan.setKind("PLAN"); plan.setSystemKey("PLAN"); plan.setTitle("制定今日计划");
        plan.setCategory("WORK"); plan.setScheduleType("WORKDAY"); plan.setWeekdaysJson("[]");
        plan.setStartTime(LocalTime.of(7, 0)); plan.setDueTime(LocalTime.of(9, 0)); plan.setDueNextDay(false);
        plan.setReminderNextDay(false); plan.setActive(true); plan.setOrd(10);
        plan.setNotifyDirect(false); plan.setNotifyChat(false); plan.setNotifyWebhook(false);
        when(items.selectList(any(Wrapper.class))).thenReturn(List.of(plan));

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        planOccurrence = new WorkdayRhythmChecklistOccurrenceEntity(); planOccurrence.setId("occ-plan");
        planOccurrence.setItemId("plan"); planOccurrence.setMemberId("m1"); planOccurrence.setOccurrenceDate(today);
        planOccurrence.setKind("PLAN"); planOccurrence.setCategory("WORK"); planOccurrence.setTitle("制定今日计划");
        planOccurrence.setStartTime(LocalTime.of(7, 0)); planOccurrence.setDueTime(LocalTime.of(9, 0));
        planOccurrence.setDueNextDay(false);
        when(occurrences.selectOne(any(Wrapper.class))).thenReturn(planOccurrence);
        when(occurrences.selectList(any(Wrapper.class))).thenReturn(List.of(planOccurrence));
        when(deliveries.selectCount(any(Wrapper.class))).thenReturn(0L);
    }

    @Test
    void savingThreeFocusItemsCompletesPlanOnlyAfterPreferenceIsPersisted() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        when(preferences.find("m1", "workday-rhythm-day-" + today)).thenReturn(null);

        service.saveFocus("m1", today, new SaveWorkdayRhythmFocusRequest(List.of("上线", "验收", "复盘")));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(preferences).upsert(org.mockito.ArgumentMatchers.eq("m1"),
                org.mockito.ArgumentMatchers.eq("workday-rhythm-day-" + today), json.capture());
        assertThat(json.getValue()).contains("上线", "验收", "复盘");
        assertThat(planOccurrence.getCompletedAt()).isNotNull();
        verify(occurrences, atLeastOnce()).updateById(planOccurrence);
    }

    @Test
    void bigWeekSundayBecomesWorkdayAndCanBeOverriddenForOnlyThatDate() {
        LocalDate sunday = LocalDate.parse("2026-08-23");
        when(preferences.find("m1", "alternate-work-week"))
                .thenReturn("{\"enabled\":true,\"anchorWeek\":\"2026-08-17\",\"anchorType\":\"big\"}");
        planOccurrence.setOccurrenceDate(sunday);

        var dashboard = service.dashboard("m1", sunday);

        assertThat(dashboard.workday()).isTrue();
        assertThat(dashboard.workWeekLabel()).contains("大周", "周日上班");
        assertThat(dashboard.checklist()).extracting(value -> value.kind()).contains("PLAN");
    }
}
