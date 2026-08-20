package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.MemberPreferenceEntity;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class WorkdayRhythmHistoryServiceTest {

    private final MemberPreferenceMapper preferences = mock(MemberPreferenceMapper.class);
    private final WorkdayRhythmHistoryService service = new WorkdayRhythmHistoryService(
            preferences, new ObjectMapper());

    @Test
    void returnsOnlyCurrentMembersPersistedHistoryAndKeepsDailyItemSnapshot() {
        when(preferences.find("m1", WorkdayRhythmHistoryService.CONFIG_KEY)).thenReturn("""
                {"energyItems":[
                  {"id":"WATER","title":"当前饮水名称","mode":"COUNT"},
                  {"id":"WALK","title":"散步","mode":"DAILY_ONCE"},
                  {"id":"LATER","title":"后来新增","mode":"COUNT"}
                ]}
                """);
        when(preferences.findRange("m1", "workday-rhythm-day-2026-08-01",
                "workday-rhythm-day-2026-08-31")).thenReturn(List.of(
                        entry("workday-rhythm-day-2026-08-20", """
                                {"version":1,"date":"2026-08-20","workdayActive":true,
                                 "focus":["完成上线验收","","整理复盘"],
                                 "energyRecords":{"WATER":3,"WALK":1,"REMOVED":1},
                                 "energySnapshot":{"WATER":{"title":"当天饮水名称","mode":"COUNT"},
                                   "WALK":{"title":"午后散步","mode":"DAILY_ONCE"}}}
                                """),
                        entry("workday-rhythm-day-2026-08-19", "not-json"),
                        entry("workday-rhythm-day-2026-08-18", """
                                {"version":1,"date":"2026-08-18"}
                                """)));

        var result = service.history("m1", LocalDate.parse("2026-08-01"),
                LocalDate.parse("2026-08-31"));

        assertThat(result.items()).hasSize(1);
        assertThat(result.skippedCount()).isEqualTo(2);
        assertThat(result.items().get(0).focus()).containsExactly("完成上线验收", "整理复盘");
        assertThat(result.items().get(0).energyItems())
                .extracting(item -> item.title() + ":" + item.count())
                .containsExactly("当天饮水名称:3", "午后散步:1", "已移除项目:1");
        assertThat(result.items().get(0).energyItems().get(2).mode()).isEqualTo("COUNT");
        verify(preferences).findRange("m1", "workday-rhythm-day-2026-08-01",
                "workday-rhythm-day-2026-08-31");
    }

    @Test
    void rejectsInvalidOrUnboundedRange() {
        assertThatThrownBy(() -> service.history("m1", LocalDate.parse("2026-08-21"),
                LocalDate.parse("2026-08-20")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("起止日期");
        assertThatThrownBy(() -> service.history("m1", LocalDate.parse("2025-01-01"),
                LocalDate.parse("2026-08-20")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("366 天");
    }

    private MemberPreferenceEntity entry(String key, String value) {
        MemberPreferenceEntity entry = new MemberPreferenceEntity();
        entry.setMemberId("m1");
        entry.setPrefKey(key);
        entry.setPrefValue(value);
        return entry;
    }
}
