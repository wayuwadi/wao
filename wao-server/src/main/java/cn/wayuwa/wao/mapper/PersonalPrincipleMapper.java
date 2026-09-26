package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.PersonalPrincipleCheckinEntity;
import cn.wayuwa.wao.domain.PersonalPrincipleEntity;
import cn.wayuwa.wao.domain.PersonalPrincipleTargetEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 准则、受邀成员与本人记录的持久化访问。 */
@Mapper
public interface PersonalPrincipleMapper extends BaseMapper<PersonalPrincipleEntity> {
    @Select("SELECT DISTINCT p.* FROM personal_principle p LEFT JOIN personal_principle_target t ON t.principle_id=p.id WHERE p.owner_member_id=#{memberId} OR t.member_id=#{memberId} ORDER BY p.updated_at DESC")
    List<PersonalPrincipleEntity> visible(@Param("memberId") String memberId);

    @Select("SELECT * FROM personal_principle_target WHERE principle_id=#{principleId} ORDER BY member_id")
    List<PersonalPrincipleTargetEntity> targets(@Param("principleId") String principleId);

    @Select("SELECT * FROM personal_principle_target WHERE principle_id=#{principleId} AND member_id=#{memberId}")
    PersonalPrincipleTargetEntity target(@Param("principleId") String principleId, @Param("memberId") String memberId);

    @Insert("INSERT INTO personal_principle_target(principle_id,member_id,consent,updated_at) VALUES(#{principleId},#{memberId},#{consent},#{updatedAt})")
    int insertTarget(PersonalPrincipleTargetEntity target);

    @Update("UPDATE personal_principle_target SET consent=#{consent},updated_at=#{updatedAt} WHERE principle_id=#{principleId} AND member_id=#{memberId}")
    int updateTarget(PersonalPrincipleTargetEntity target);

    @Delete("DELETE FROM personal_principle_target WHERE principle_id=#{principleId} AND member_id=#{memberId}")
    int removeTarget(@Param("principleId") String principleId, @Param("memberId") String memberId);

    @Select("SELECT * FROM personal_principle_checkin WHERE principle_id=#{principleId} AND member_id=#{memberId} AND checkin_date=#{date}")
    PersonalPrincipleCheckinEntity checkin(@Param("principleId") String principleId,
            @Param("memberId") String memberId, @Param("date") LocalDate date);

    @Insert("INSERT INTO personal_principle_checkin(principle_id,member_id,checkin_date,status,note,principle_version,updated_at) VALUES(#{principleId},#{memberId},#{checkinDate},#{status},#{note},#{principleVersion},#{updatedAt}) ON DUPLICATE KEY UPDATE status=VALUES(status),note=VALUES(note),principle_version=VALUES(principle_version),updated_at=VALUES(updated_at)")
    int saveCheckin(PersonalPrincipleCheckinEntity checkin);

    @Update("UPDATE personal_principle SET title=#{rule.title},category=#{rule.category},trigger_text=#{rule.triggerText},desired_action=#{rule.desiredAction},exception_text=#{rule.exceptionText},reminder_time=#{rule.reminderTime},evidence_source=#{rule.evidenceSource},paused=#{rule.paused},version=version+1,updated_at=#{rule.updatedAt} WHERE id=#{rule.id} AND version=#{expectedVersion}")
    int updateVersioned(@Param("rule") PersonalPrincipleEntity rule, @Param("expectedVersion") int expectedVersion);

    @Insert("INSERT INTO personal_principle_revision(principle_id,version,snapshot_json,changed_at) VALUES(#{id},#{version},#{snapshot},#{changedAt})")
    int insertRevision(@Param("id") String id, @Param("version") int version,
            @Param("snapshot") String snapshot, @Param("changedAt") LocalDateTime changedAt);

    @Select("SELECT snapshot_json FROM personal_principle_revision WHERE principle_id=#{id} ORDER BY version DESC")
    List<String> revisions(@Param("id") String id);
}
