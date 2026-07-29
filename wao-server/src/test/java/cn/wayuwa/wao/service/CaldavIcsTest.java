package cn.wayuwa.wao.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CaldavIcsTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 7, 1, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 7, 31, 23, 59);

    private static String wrap(String vevent) {
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\n" + vevent + "END:VCALENDAR\r\n";
    }

    @Test
    void 普通定时事件按本地时区展开() {
        String ics = wrap("""
                BEGIN:VEVENT
                UID:e1
                SUMMARY:体检
                LOCATION:市一医院
                DTSTART:20260710T013000Z
                DTEND:20260710T023000Z
                END:VEVENT
                """.replace("\n", "\r\n"));
        List<CaldavIcs.Occurrence> out = CaldavIcs.expand(ics, FROM, TO, ZONE);
        assertThat(out).hasSize(1);
        CaldavIcs.Occurrence occ = out.get(0);
        assertThat(occ.summary()).isEqualTo("体检");
        assertThat(occ.location()).isEqualTo("市一医院");
        assertThat(occ.allDay()).isFalse();
        assertThat(occ.start()).isEqualTo(LocalDateTime.of(2026, 7, 10, 9, 30));
        assertThat(occ.end()).isEqualTo(LocalDateTime.of(2026, 7, 10, 10, 30));
    }

    @Test
    void 全天事件的DTEND排他端点转成最后一天() {
        String ics = wrap("""
                BEGIN:VEVENT
                UID:e2
                SUMMARY:出差
                DTSTART;VALUE=DATE:20260713
                DTEND;VALUE=DATE:20260715
                END:VEVENT
                """.replace("\n", "\r\n"));
        List<CaldavIcs.Occurrence> out = CaldavIcs.expand(ics, FROM, TO, ZONE);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).allDay()).isTrue();
        assertThat(out.get(0).start().toLocalDate()).isEqualTo("2026-07-13");
        assertThat(out.get(0).end().toLocalDate()).isEqualTo("2026-07-14");
    }

    @Test
    void 周重复规则只展开窗口内的发生并跳过EXDATE() {
        String ics = wrap("""
                BEGIN:VEVENT
                UID:e3
                SUMMARY:周会
                DTSTART;TZID=Asia/Shanghai:20260601T100000
                DTEND;TZID=Asia/Shanghai:20260601T110000
                RRULE:FREQ=WEEKLY;BYDAY=MO
                EXDATE;TZID=Asia/Shanghai:20260713T100000
                END:VEVENT
                """.replace("\n", "\r\n"));
        List<CaldavIcs.Occurrence> out = CaldavIcs.expand(ics, FROM, TO, ZONE);
        // 7 月的周一：6/13/20/27，EXDATE 去掉 13 号
        assertThat(out).extracting(o -> o.start().toLocalDate().toString())
                .containsExactly("2026-07-06", "2026-07-20", "2026-07-27");
    }

    @Test
    void RECURRENCEID改期覆盖原始发生() {
        String ics = wrap("""
                BEGIN:VEVENT
                UID:e4
                SUMMARY:复盘
                DTSTART;TZID=Asia/Shanghai:20260701T140000
                DTEND;TZID=Asia/Shanghai:20260701T150000
                RRULE:FREQ=WEEKLY;COUNT=2
                END:VEVENT
                BEGIN:VEVENT
                UID:e4
                SUMMARY:复盘（改期）
                RECURRENCE-ID;TZID=Asia/Shanghai:20260708T140000
                DTSTART;TZID=Asia/Shanghai:20260709T140000
                DTEND;TZID=Asia/Shanghai:20260709T150000
                END:VEVENT
                """.replace("\n", "\r\n"));
        List<CaldavIcs.Occurrence> out = CaldavIcs.expand(ics, FROM, TO, ZONE);
        assertThat(out).extracting(o -> o.start().toLocalDate().toString())
                .containsExactlyInAnyOrder("2026-07-01", "2026-07-09");
    }

    @Test
    void 窗口外的事件不返回且解析失败时不抛异常() {
        String ics = wrap("""
                BEGIN:VEVENT
                UID:e5
                SUMMARY:去年的事
                DTSTART:20250710T013000Z
                DTEND:20250710T023000Z
                END:VEVENT
                """.replace("\n", "\r\n"));
        assertThat(CaldavIcs.expand(ics, FROM, TO, ZONE)).isEmpty();
        assertThat(CaldavIcs.expand("not an ics", FROM, TO, ZONE)).isEmpty();
    }

    @Test
    void 缺标题时给出占位标题() {
        String ics = wrap("""
                BEGIN:VEVENT
                UID:e6
                DTSTART:20260710T013000Z
                END:VEVENT
                """.replace("\n", "\r\n"));
        List<CaldavIcs.Occurrence> out = CaldavIcs.expand(ics, FROM, TO, ZONE);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).summary()).isEqualTo("（无标题）");
    }
}
