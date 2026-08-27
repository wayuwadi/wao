package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Set;

import cn.wayuwa.wao.dto.HolidayEventDto;
import org.junit.jupiter.api.Test;

class HolidayServiceTest {

    private final HolidayService service = new HolidayService();

    @Test
    void returnsOfficialChineseScheduleAndMothersDay() {
        var events = service.events(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 10),
                Set.of(HolidayService.FAMILY));

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
            assertThat(event.internationalCategory()).isEqualTo(HolidayService.FAMILY);
            assertThat(event.remindable()).isTrue();
        });
    }

    @Test
    void calculatesLunarFestivalsWithoutInventingFutureDayOffSchedule() {
        var springFestival = service.events(LocalDate.of(2026, 2, 16), LocalDate.of(2026, 2, 18), Set.of());
        assertThat(springFestival).anyMatch(event -> event.date().equals("2026-02-17")
                && event.name().equals("春节") && event.dayType().equals("DAY_OFF"));

        var future = service.events(LocalDate.of(2030, 1, 30), LocalDate.of(2030, 2, 5), Set.of());
        assertThat(future).anyMatch(event -> event.name().equals("春节")
                && event.dayType().equals("FESTIVAL"));
        assertThat(future).noneMatch(event -> !event.dayType().equals("FESTIVAL"));
    }

    @Test
    void returnsWesternEasterByCalendarRule() {
        assertThat(service.events(LocalDate.of(2026, 4, 5), LocalDate.of(2026, 4, 5),
                Set.of(HolidayService.CHRISTIAN)))
                .extracting(HolidayEventDto::name)
                .contains("复活节", "清明节");
    }

    @Test
    void exposesAllSolarTermsAsRealCalendarNodes() {
        assertThat(service.events(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), Set.of()))
                .filteredOn(event -> event.name().startsWith("节气 · "))
                .hasSize(24)
                .extracting(HolidayEventDto::name)
                .contains("节气 · 立春", "节气 · 春分", "节气 · 夏至", "节气 · 冬至");
    }

    @Test
    void doesNotAddInternationalFestivalsUntilCategoryIsSelected() {
        LocalDate mothersDay = LocalDate.of(2026, 5, 10);
        assertThat(service.events(mothersDay, mothersDay, Set.of()))
                .extracting(HolidayEventDto::name).doesNotContain("母亲节");
        assertThat(service.events(mothersDay, mothersDay, Set.of(HolidayService.FAMILY)))
                .extracting(HolidayEventDto::name).contains("母亲节");
    }

    @Test
    void marksKnownScheduleSourceAndUnknownYearCoverage() {
        assertThat(service.officialScheduleAvailable(2026)).isTrue();
        assertThat(service.officialScheduleAvailable(2030)).isFalse();
        assertThat(service.events(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 1), Set.of()))
                .singleElement().satisfies(event -> {
                    assertThat(event.year()).isEqualTo(2026);
                    assertThat(event.sourceName()).contains("国务院办公厅");
                    assertThat(event.sourceUrl()).contains("gov.cn");
                });
    }
}
