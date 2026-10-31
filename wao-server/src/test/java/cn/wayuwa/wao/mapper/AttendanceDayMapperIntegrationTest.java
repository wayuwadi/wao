package cn.wayuwa.wao.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import cn.wayuwa.wao.domain.AttendanceDayEntity;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:attendanceleave;DB_CLOSE_DELAY=-1;MODE=MySQL")
class AttendanceDayMapperIntegrationTest {
    @Autowired AttendanceDayMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test void resyncReplacesLeaveDetailsWithoutOverwritingManualValuesOrOtherMembers() {
        jdbc.execute("""
            CREATE TABLE attendance_day (
              member_id VARCHAR(32), attendance_date DATE, remote_clock_in_at TIMESTAMP, remote_clock_out_at TIMESTAMP,
              remote_day_type VARCHAR(16), remote_anomaly VARCHAR(512), remote_leaves TEXT,
              manual_clock_in_at TIMESTAMP, manual_clock_out_at TIMESTAMP, manual_day_type VARCHAR(16),
              manual_override BOOLEAN DEFAULT FALSE, synced_at TIMESTAMP, created_at TIMESTAMP, updated_at TIMESTAMP,
              PRIMARY KEY(member_id,attendance_date))
            """);
        LocalDate date=LocalDate.of(2026,9,2);
        AttendanceDayEntity value=new AttendanceDayEntity();
        value.setMemberId("member-a");value.setAttendanceDate(date);value.setRemoteDayType("LEAVE");
        value.setRemoteLeaves("[{\"title\":\"事假\"}]");value.setManualOverride(false);
        mapper.upsertRemote(value);
        value.setMemberId("member-b");mapper.upsertRemote(value);value.setMemberId("member-a");
        value.setManualClockInAt(date.atTime(9,0));value.setManualDayType("WORKDAY");mapper.upsertManual(value);
        value.setRemoteDayType("WORKDAY");value.setRemoteLeaves("[]");mapper.upsertRemote(value);
        var stored=mapper.find("member-a",date);
        assertThat(stored.getManualOverride()).isTrue();
        assertThat(stored.getManualClockInAt()).isEqualTo(date.atTime(9,0));
        assertThat(stored.getRemoteLeaves()).isEqualTo("[]");
        mapper.clearManual("member-a",date,LocalDateTime.now());
        assertThat(mapper.find("member-a",date).getRemoteDayType()).isEqualTo("WORKDAY");
        assertThat(mapper.find("member-b",date).getRemoteLeaves()).contains("事假");
    }
}
