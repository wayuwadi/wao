package cn.wayuwa.wao.mapper;

import java.util.List;
import cn.wayuwa.wao.domain.SkillVersionEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SkillVersionMapper extends BaseMapper<SkillVersionEntity> {
    String META = "id,skill_id,version,display_name,summary,category,tags_json,changelog,license_code,rights_confirmed,tested_platforms_json,compatible_platforms_json,state," +
            "package_name,package_size,unpacked_size,file_count,sha256,file_manifest_json,scan_findings_json,skill_md," +
            "submitted_at,reviewed_at,reviewer_id,review_note,created_at,updated_at";
    @Select("SELECT " + META + " FROM skill_version WHERE skill_id=#{skillId} ORDER BY created_at DESC")
    List<SkillVersionEntity> listMeta(String skillId);
    @Select("SELECT " + META + " FROM skill_version WHERE id=#{id}")
    SkillVersionEntity findMeta(String id);
    @Select("SELECT " + META + " FROM skill_version WHERE state='PENDING_REVIEW' ORDER BY submitted_at")
    List<SkillVersionEntity> reviewQueue();
    @Select("SELECT COUNT(*) FROM skill_version v JOIN skill s ON s.id=v.skill_id JOIN skill_publisher p ON p.id=s.publisher_id WHERE p.member_id=#{memberId} AND v.state IN ('DRAFT','PENDING_REVIEW')")
    long countOpenByMember(String memberId);
}
