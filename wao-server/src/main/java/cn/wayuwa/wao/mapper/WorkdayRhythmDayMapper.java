package cn.wayuwa.wao.mapper;
import cn.wayuwa.wao.domain.WorkdayRhythmDayEntity;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
@Mapper public interface WorkdayRhythmDayMapper {
    @Select("SELECT * FROM workday_rhythm_day WHERE member_id=#{memberId} AND rhythm_date=#{date}")
    WorkdayRhythmDayEntity find(@Param("memberId") String memberId, @Param("date") LocalDate date);
    @Select("SELECT * FROM workday_rhythm_day WHERE member_id=#{memberId} AND rhythm_date=#{date} FOR UPDATE")
    WorkdayRhythmDayEntity lock(@Param("memberId") String memberId, @Param("date") LocalDate date);
    @Select("SELECT * FROM workday_rhythm_day WHERE member_id=#{memberId} AND rhythm_date BETWEEN #{from} AND #{to}")
    List<WorkdayRhythmDayEntity> findRange(@Param("memberId") String memberId, @Param("from") LocalDate from, @Param("to") LocalDate to);
    @Insert("INSERT INTO workday_rhythm_day(member_id,rhythm_date,workday_override,document_id,created_at,updated_at) VALUES(#{memberId},#{rhythmDate},#{workdayOverride},#{documentId},#{createdAt},#{updatedAt})")
    int insert(WorkdayRhythmDayEntity value);
    @Update("UPDATE workday_rhythm_day SET workday_override=#{value},updated_at=#{now} WHERE member_id=#{memberId} AND rhythm_date=#{date}")
    int updateOverride(@Param("memberId") String memberId, @Param("date") LocalDate date, @Param("value") Boolean value, @Param("now") LocalDateTime now);
    @Update("UPDATE workday_rhythm_day SET document_id=#{documentId},updated_at=#{now} WHERE member_id=#{memberId} AND rhythm_date=#{date} AND document_id IS NULL")
    int bindDocument(@Param("memberId") String memberId, @Param("date") LocalDate date, @Param("documentId") String documentId, @Param("now") LocalDateTime now);
}
