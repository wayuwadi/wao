package cn.wayuwa.wao.mapper;
import cn.wayuwa.wao.domain.AttendanceConnectorEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
@Mapper public interface AttendanceConnectorMapper extends BaseMapper<AttendanceConnectorEntity> {
    @Update("""
        UPDATE attendance_connector SET history_backfill_claim_token=#{token}, history_backfill_claimed_at=#{now}, updated_at=#{now}
        WHERE member_id=#{memberId} AND enabled=1
          AND (history_backfill_claimed_at IS NULL OR history_backfill_claimed_at < #{expiredAt})
        """)
    int claimHistoryBackfill(@Param("memberId") String memberId, @Param("token") String token,
            @Param("now") LocalDateTime now, @Param("expiredAt") LocalDateTime expiredAt);

    @Update("""
        UPDATE attendance_connector SET
          history_synced_from=CASE WHEN history_synced_from IS NULL OR history_synced_from > #{syncedFrom} THEN #{syncedFrom} ELSE history_synced_from END,
          history_backfill_claim_token=NULL, history_backfill_claimed_at=NULL, updated_at=#{now}
        WHERE member_id=#{memberId} AND history_backfill_claim_token=#{token}
        """)
    int advanceHistoryBackfill(@Param("memberId") String memberId, @Param("syncedFrom") LocalDate syncedFrom,
            @Param("token") String token, @Param("now") LocalDateTime now);

    @Update("UPDATE attendance_connector SET history_backfill_claim_token=NULL, history_backfill_claimed_at=NULL WHERE member_id=#{memberId} AND history_backfill_claim_token=#{token}")
    int releaseHistoryBackfill(@Param("memberId") String memberId, @Param("token") String token);

    @Update("UPDATE attendance_connector SET history_synced_from=NULL, history_backfill_claim_token=NULL, history_backfill_claimed_at=NULL, updated_at=#{now} WHERE member_id=#{memberId}")
    int resetHistoryBackfill(@Param("memberId") String memberId, @Param("now") LocalDateTime now);
}
