package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.domain.MemberPreferenceEntity;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PendingRhythmFocusServiceTest {
    private final MemberPreferenceMapper preferences = mock(MemberPreferenceMapper.class);
    private final ObjectMapper json = new ObjectMapper();
    private final PendingRhythmFocusService service = new PendingRhythmFocusService(preferences, json);
    private final LocalDate today = LocalDate.of(2027, 1, 2);
    private final LocalDate yesterday = today.minusDays(1);
    private final String key = PendingRhythmFocusService.PREFIX + yesterday;

    @Test
    void readsOnlyYesterdayThreeGoalsAndNeverScansPersonalOrEarlierTasks() {
        when(preferences.find("m1", key)).thenReturn(
                entry(yesterday, "[\"已完成\",\"昨日目标\",\" \",\"旧版第四项\"]", "[true,false,false,false]").getPrefValue());
        var result = service.pending("m1", today);
        assertThat(result.items()).extracting(item -> item.title()).containsExactly("昨日目标");
        assertThat(result.items().get(0).index()).isEqualTo(1);
        assertThat(result.items().get(0).date()).isEqualTo(yesterday);
        assertThat(result.skippedCount()).isZero();
        verify(preferences).find("m1", key);
        verifyNoMoreInteractions(preferences);
    }

    @Test
    void missingYesterdayDoesNotFallBackToOlderArchives() {
        assertThat(service.pending("m1", today).items()).isEmpty();
        assertThat(service.pending("m1", today).skippedCount()).isZero();
        verify(preferences, times(2)).find("m1", key);
        verifyNoMoreInteractions(preferences);
    }

    @Test
    void yesterdayAcrossYearBoundaryUsesTheExactPreviousNaturalDay() {
        var newYear = LocalDate.of(2027, 1, 1);
        var previous = newYear.minusDays(1);
        when(preferences.find("m1", PendingRhythmFocusService.PREFIX + previous)).thenReturn(
                entry(previous, "[\"跨年目标\"]", null).getPrefValue());
        assertThat(service.pending("m1", newYear).items()).extracting(item -> item.date()).containsExactly(previous);
    }

    @Test
    void corruptRecordsAreCountedAndNeverInventedAsEmptySuccess() {
        var corrupt = entry(yesterday, "[\"待办\"]", "[\"true\"]");
        when(preferences.find("m1", key)).thenReturn(corrupt.getPrefValue());
        assertThat(service.pending("m1", today).skippedCount()).isEqualTo(1);
    }

    @Test
    void updatesOnlyOriginalCompletionAndRetainsAllOtherFields() throws Exception {
        String raw = entry(yesterday, "[\"第一项\",\"第二项\"]", "[true,false]").getPrefValue();
        when(preferences.find("m1", key)).thenReturn(raw);
        when(preferences.compareAndSet(eq("m1"), eq(key), eq(raw), anyString())).thenReturn(1);
        service.complete("m1", yesterday, today, 1, "第二项", true);
        var saved = ArgumentCaptor.forClass(String.class);
        verify(preferences).compareAndSet(eq("m1"), eq(key), eq(raw), saved.capture());
        var result = json.readTree(saved.getValue());
        assertThat(result.path("focusCompleted").toString()).isEqualTo("[true,true]");
        assertThat(result.path("waterCount").intValue()).isEqualTo(3);
        assertThat(result.path("unknownField").textValue()).isEqualTo("保留");
        assertThat(result.path("focus")).isEqualTo(json.readTree(raw).path("focus"));
    }

    @Test
    void completionRoundTripDisappearsAndUndoReturnsOriginalWithoutDuplicates() {
        var entry = entry(yesterday, "[\"遗留重点\"]", null);
        when(preferences.find("m1", key)).thenAnswer(call -> entry.getPrefValue());
        when(preferences.compareAndSet(eq("m1"), eq(key), anyString(), anyString())).thenAnswer(call -> {
            entry.setPrefValue(call.getArgument(3)); return 1;
        });
        assertThat(service.pending("m1", today).items()).hasSize(1);
        service.complete("m1", yesterday, today, 0, "遗留重点", true);
        assertThat(service.pending("m1", today).items()).isEmpty();
        service.complete("m1", yesterday, today, 0, "遗留重点", true);
        verify(preferences, times(1)).compareAndSet(anyString(), anyString(), anyString(), anyString());
        service.complete("m1", yesterday, today, 0, "遗留重点", false);
        assertThat(service.pending("m1", today).items()).hasSize(1);
    }

    @Test
    void editingConflictAndConcurrentUpdateNeverOverwriteOriginal() {
        String raw = entry(yesterday, "[\"已经改名\"]", null).getPrefValue();
        when(preferences.find("m1", key)).thenReturn(raw);
        assertThatThrownBy(() -> service.complete("m1", yesterday, today, 0, "旧文本", true)).isInstanceOf(ConflictException.class);
        verify(preferences, never()).compareAndSet(anyString(), anyString(), anyString(), anyString());
        assertThatThrownBy(() -> service.complete("m1", yesterday, today, 0, "已经改名", true)).isInstanceOf(ConflictException.class);
    }

    @Test
    void cannotWriteEarlierTodayFutureOrBeyondThreeGoals() {
        assertThatThrownBy(() -> service.complete("m1", today, today, 0, "今日草稿", true)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.complete("m1", today.plusDays(1), today, 0, "明日计划", true)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.complete("m1", yesterday, today, 20, "无效", true)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.complete("m1", today.minusDays(2), today, 0, "更早目标", true)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.complete("m1", yesterday, today, 3, "第四项", true)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(preferences);
    }

    private MemberPreferenceEntity entry(LocalDate date, String focus, String states) {
        var value = new MemberPreferenceEntity();
        value.setMemberId("m1");
        value.setPrefKey(PendingRhythmFocusService.PREFIX + date);
        value.setPrefValue("{\"version\":1,\"date\":\"" + date + "\",\"focus\":" + focus
                + (states == null ? "" : ",\"focusCompleted\":" + states) + ",\"waterCount\":3,\"unknownField\":\"保留\"}");
        return value;
    }
}
