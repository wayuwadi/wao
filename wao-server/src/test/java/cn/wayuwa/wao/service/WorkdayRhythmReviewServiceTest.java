package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.dto.WorkdayRhythmReviewDto;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WorkdayRhythmReviewServiceTest {
    private final MemberPreferenceMapper preferences = mock(MemberPreferenceMapper.class);
    private final WorkdayRhythmReviewService service = new WorkdayRhythmReviewService(preferences, new ObjectMapper());
    private final LocalDate date = LocalDate.of(2026, 9, 9);
    private final String key = WorkdayRhythmReviewService.PREFIX + date;

    @Test void emptyReadDoesNotMaterializeAndFirstSaveKeepsOriginalText() {
        assertThat(service.get("m1", date)).isEqualTo(new WorkdayRhythmReviewDto(0, List.of()));
        when(preferences.insertIfAbsent(eq("m1"), eq(key), anyString())).thenReturn(1);
        var result = service.save("m1", date, new WorkdayRhythmReviewDto(0, List.of("做得好\n下次继续", "", " 痛点 ")));
        assertThat(result.revision()).isEqualTo(1);
        assertThat(result.entries()).containsExactly("做得好\n下次继续", "", " 痛点 ");
        verify(preferences, never()).upsert(anyString(), anyString(), anyString());
    }
    @Test void rejectsStaleAndConcurrentFirstCreationWithoutOverwriting() {
        when(preferences.find("m1", key)).thenReturn("{\"revision\":2,\"entries\":[\"另一个页面\"]}");
        assertThatThrownBy(() -> service.save("m1", date, new WorkdayRhythmReviewDto(1, List.of("本机草稿"))))
                .isInstanceOf(ConflictException.class);
        when(preferences.find("m1", key)).thenReturn(null);
        assertThatThrownBy(() -> service.save("m1", date, new WorkdayRhythmReviewDto(0, List.of("本机草稿"))))
                .isInstanceOf(ConflictException.class);
        verify(preferences, never()).compareAndSet(anyString(), anyString(), anyString(), anyString());
    }
    @Test void retriesIdenticalContentAndUsesCompareAndSetForUpdates() {
        String raw = "{\"revision\":2,\"entries\":[\"已保存\"]}";
        when(preferences.find("m1", key)).thenReturn(raw);
        assertThat(service.save("m1", date, new WorkdayRhythmReviewDto(1, List.of("已保存"))).revision()).isEqualTo(2);
        when(preferences.compareAndSet(eq("m1"), eq(key), eq(raw), anyString())).thenReturn(1);
        assertThat(service.save("m1", date, new WorkdayRhythmReviewDto(2, List.of("修改"))).revision()).isEqualTo(3);
        when(preferences.compareAndSet(eq("m1"), eq(key), eq(raw), anyString())).thenReturn(0);
        assertThatThrownBy(() -> service.save("m1", date, new WorkdayRhythmReviewDto(2, List.of("并发"))))
                .isInstanceOf(ConflictException.class);
    }
    @Test void corruptDataAndOversizedInputNeverBecomeEmptySuccess() {
        when(preferences.find("m1", key)).thenReturn("{\"revision\":1,\"entries\":[3]}");
        assertThatThrownBy(() -> service.get("m1", date)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.save("m1", date, new WorkdayRhythmReviewDto(0, List.of("字".repeat(1001)))))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.save("m1", date, new WorkdayRhythmReviewDto(0, java.util.Collections.nCopies(21, "条"))))
                .isInstanceOf(BadRequestException.class);
        assertThat(service.get("m2", date).entries()).isEmpty();
        verify(preferences).find("m2", key);
    }
}
