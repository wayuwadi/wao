package cn.wayuwa.wao.service;

import com.nlf.calendar.Lunar;
import com.nlf.calendar.Solar;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

/** Date-only arithmetic uses the member-facing Shanghai calendar, never elapsed 24-hour buckets. */
public final class RoutineCalendar {
    private RoutineCalendar() {}
    public static LocalDate date(String value) { return LocalDate.parse(value); }
    public static LocalDate next(RoutineService.Item item, LocalDate today) {
        LocalDate base = date(item.date);
        if (item.repeat.equals("none") || !base.isBefore(today)) return base;
        if (item.calendar.equals("lunar")) {
            Lunar lunar = Solar.fromYmd(base.getYear(), base.getMonthValue(), base.getDayOfMonth()).getLunar();
            for (int year = today.getYear() - 1; year <= today.getYear() + 12; year++) {
                try {
                    var value = Lunar.fromYmd(year, lunar.getMonth(), lunar.getDay()).getSolar();
                    LocalDate candidate = LocalDate.of(value.getYear(), value.getMonth(), value.getDay());
                    if (!candidate.isBefore(today) && !candidate.isBefore(base)) return candidate;
                } catch (RuntimeException ignored) { /* A leap month or lunar day may not exist this year. */ }
            }
            return null;
        }
        return switch (item.repeat) {
            case "weekly" -> base.plusWeeks((ChronoUnit.DAYS.between(base, today) + 6) / 7);
            case "monthly" -> {
                long months = ChronoUnit.MONTHS.between(YearMonth.from(base), YearMonth.from(today));
                LocalDate value = base.plusMonths(months);
                yield value.isBefore(today) ? base.plusMonths(months + 1) : value;
            }
            case "yearly" -> {
                LocalDate value = base.plusYears(today.getYear() - base.getYear());
                yield value.isBefore(today) ? base.plusYears(today.getYear() - base.getYear() + 1L) : value;
            }
            default -> base;
        };
    }
    public static boolean scheduled(RoutineService.Item item, LocalDate day) {
        LocalDate start = date(item.date);
        long offset = ChronoUnit.DAYS.between(start, day);
        if (offset < 0 || (item.durationDays > 0 && offset >= item.durationDays)) return false;
        return switch (item.frequency) {
            case "weekly" -> true;
            case "interval" -> offset % item.intervalDays == 0;
            default -> item.weekdays.contains(day.getDayOfWeek().getValue());
        };
    }
    public static LocalDate monday(LocalDate day) { return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); }
    public static String lunarLabel(LocalDate day) {
        return Solar.fromYmd(day.getYear(), day.getMonthValue(), day.getDayOfMonth()).getLunar().toString();
    }
}
