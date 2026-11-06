package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TaskTimeCalendarTest {
    TaskTimeRule rule(String repeat,int day){var r=new TaskTimeRule();r.repeat=repeat;r.anchorDay=day;return r;}
    @Test void monthEndAndLeapYearKeepOriginalAnchor(){
        var r=rule("monthly",31);
        assertThat(TaskTimeCalendar.next(r,LocalDate.parse("2026-01-31"),LocalDate.parse("2026-02-01"))).isEqualTo(LocalDate.parse("2026-02-28"));
        assertThat(TaskTimeCalendar.next(r,LocalDate.parse("2026-02-28"),LocalDate.parse("2026-03-01"))).isEqualTo(LocalDate.parse("2026-03-31"));
        r.repeat="yearly";r.anchorDay=29;
        assertThat(TaskTimeCalendar.next(r,LocalDate.parse("2027-02-28"),LocalDate.parse("2027-03-01"))).isEqualTo(LocalDate.parse("2028-02-29"));
    }
    @Test void plannedRecurrenceSkipsMissedPeriodsButCompletionUsesActualCompletion(){
        var r=rule("daily",1);r.interval=3;
        assertThat(TaskTimeCalendar.next(r,LocalDate.parse("2026-11-01"),LocalDate.parse("2026-11-06"))).isEqualTo(LocalDate.parse("2026-11-07"));
        r.mode="completion";
        assertThat(TaskTimeCalendar.next(r,LocalDate.parse("2026-11-06"),LocalDate.parse("2026-11-06"))).isEqualTo(LocalDate.parse("2026-11-09"));
    }
    @Test void weekdaysAndEndDateAreExplicit(){
        var r=rule("weekdays",6);
        assertThat(TaskTimeCalendar.next(r,LocalDate.parse("2026-11-06"),LocalDate.parse("2026-11-06"))).isEqualTo(LocalDate.parse("2026-11-09"));
        r.until="2026-11-08";
        assertThat(TaskTimeCalendar.next(r,LocalDate.parse("2026-11-06"),LocalDate.parse("2026-11-06"))).isNull();
    }
    @Test void boundedRulesRejectInvalidIntervalsDatesAndReminderSlots(){
        var r=new TaskTimeRule();r.interval=0;assertThatThrownBy(()->TaskTimeCalendar.normalize(r)).isInstanceOf(BadRequestException.class);
        r.interval=1;r.remindBefore=List.of(-1);assertThatThrownBy(()->TaskTimeCalendar.normalize(r)).isInstanceOf(BadRequestException.class);
        r.remindBefore=List.of(10,10,0);TaskTimeCalendar.normalize(r);assertThat(r.remindBefore).containsExactly(0,10);
        r.until="2026-02-30";assertThatThrownBy(()->TaskTimeCalendar.normalize(r)).isInstanceOf(BadRequestException.class);
    }
}
