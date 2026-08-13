package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import cn.wayuwa.wao.dto.HolidayEventDto;
import org.junit.jupiter.api.Test;

class HolidayServiceTest {

    private final HolidayService service = new HolidayService();

    @Test
    void returnsOfficialChineseScheduleAndMothersDay() {
        var events = service.events(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 10));

        assertThat(events).anySatisfy(event -> {
            assertThat(event.date()).isEqualTo("2026-05-01");
            assertThat(event.name()).isEqualTo("劳动节");
            assertThat(event.category()).isEqualTo("CHINA");
            assertThat(event.dayType()).isEqualTo("DAY_OFF");
        }).anySatisfy(event -> {
            assertThat(event.date()).isEqualTo("2026-05-09");
            assertThat(event.name()).isEqualTo("劳动节调休上班");
            assertThat(event.dayType()).isEqualTo("ADJUSTED_WORKDAY");
        }).anySatisfy(event -> {
            assertThat(event.date()).isEqualTo("2026-05-10");
            assertThat(event.name()).isEqualTo("母亲节");
            assertThat(event.category()).isEqualTo("INTERNATIONAL");
        });
    }

    @Test
    void calculatesLunarFestivalsWithoutInventingFutureDayOffSchedule() {
        var springFestival = service.events(LocalDate.of(2026, 2, 16), LocalDate.of(2026, 2, 18));
        assertThat(springFestival).anyMatch(event -> event.date().equals("2026-02-17")
                && event.name().equals("春节") && event.dayType().equals("DAY_OFF"));

        var future = service.events(LocalDate.of(2030, 1, 30), LocalDate.of(2030, 2, 5));
        assertThat(future).anyMatch(event -> event.name().equals("春节")
                && event.dayType().equals("FESTIVAL"));
        assertThat(future).noneMatch(event -> !event.dayType().equals("FESTIVAL"));
    }

    @Test
    void returnsWesternEasterByCalendarRule() {
        assertThat(service.events(LocalDate.of(2026, 4, 5), LocalDate.of(2026, 4, 5)))
                .extracting(HolidayEventDto::name)
                .contains("复活节", "清明节");
    }
}
