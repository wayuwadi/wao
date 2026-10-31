package cn.wayuwa.wao.dto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
public record AttendanceDto(LocalDate from, LocalDate to, Config config, List<Day> days,
        BigDecimal totalHours, BigDecimal monthDailyAverageHours, int validDays, List<Week> weeks) {
    public record Config(boolean enabled, String username, String vaultItemId, String status,
            String lastSyncedAt, String lastError, LocalDate historySyncedFrom,
            LocalDate historyTargetFrom, boolean historyComplete) { }
    public record Day(LocalDate date, String clockInAt, String clockOutAt, String dayType, String anomaly,
            boolean manualOverride, BigDecimal workHours, boolean expectedWorkday, String workWeekLabel, List<Leave> leaves) { }
    public record Leave(String title, String startAt, String endAt, BigDecimal duration, String durationUnit) { }
    public record Week(LocalDate from, LocalDate to, String label, BigDecimal averageHours, int validDays) { }
}
