package cn.wayuwa.wao.mapper;
import cn.wayuwa.wao.domain.AttendanceDayEntity;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
@Mapper public interface AttendanceDayMapper {
    @Select("SELECT * FROM attendance_day WHERE member_id=#{memberId} AND attendance_date BETWEEN #{from} AND #{to} ORDER BY attendance_date")
    List<AttendanceDayEntity> findRange(@Param("memberId") String memberId, @Param("from") LocalDate from, @Param("to") LocalDate to);
    @Select("SELECT * FROM attendance_day WHERE member_id=#{memberId} AND attendance_date=#{date}")
    AttendanceDayEntity find(@Param("memberId") String memberId, @Param("date") LocalDate date);
    @Insert("""
        INSERT INTO attendance_day(member_id, attendance_date, remote_clock_in_at, remote_clock_out_at,
          remote_day_type, remote_anomaly, manual_clock_in_at, manual_clock_out_at, manual_day_type,
          manual_override, synced_at, created_at, updated_at)
        VALUES(#{memberId},#{attendanceDate},#{remoteClockInAt},#{remoteClockOutAt},#{remoteDayType},#{remoteAnomaly},
          #{manualClockInAt},#{manualClockOutAt},#{manualDayType},#{manualOverride},#{syncedAt},#{createdAt},#{updatedAt})
        ON DUPLICATE KEY UPDATE remote_clock_in_at=VALUES(remote_clock_in_at), remote_clock_out_at=VALUES(remote_clock_out_at),
          remote_day_type=VALUES(remote_day_type), remote_anomaly=VALUES(remote_anomaly), synced_at=VALUES(synced_at), updated_at=VALUES(updated_at)
        """)
    void upsertRemote(AttendanceDayEntity value);
    @Insert("""
        INSERT INTO attendance_day(member_id, attendance_date, manual_clock_in_at, manual_clock_out_at,
          manual_day_type, manual_override, created_at, updated_at)
        VALUES(#{memberId},#{attendanceDate},#{manualClockInAt},#{manualClockOutAt},#{manualDayType},1,#{createdAt},#{updatedAt})
        ON DUPLICATE KEY UPDATE manual_clock_in_at=VALUES(manual_clock_in_at), manual_clock_out_at=VALUES(manual_clock_out_at),
          manual_day_type=VALUES(manual_day_type), manual_override=1, updated_at=VALUES(updated_at)
        """)
    void upsertManual(AttendanceDayEntity value);
    @Update("UPDATE attendance_day SET manual_clock_in_at=NULL, manual_clock_out_at=NULL, manual_day_type=NULL, manual_override=0, updated_at=#{now} WHERE member_id=#{memberId} AND attendance_date=#{date}")
    int clearManual(@Param("memberId") String memberId, @Param("date") LocalDate date, @Param("now") LocalDateTime now);
}
