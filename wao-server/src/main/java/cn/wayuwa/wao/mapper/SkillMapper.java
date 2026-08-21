package cn.wayuwa.wao.mapper;

import java.util.List;
import cn.wayuwa.wao.domain.SkillEntity;
import cn.wayuwa.wao.domain.SkillMarketRow;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SkillMapper extends BaseMapper<SkillEntity> {
    String MARKET_COLUMNS = "SELECT s.id,s.slug,s.display_name,s.summary,s.category,s.tags_json,s.status," +
            "s.download_count,s.favorite_count,s.updated_at,s.publisher_id," +
            "p.handle publisher_handle,p.display_name publisher_name," +
            "v.id version_id,v.version,v.license_code,v.tested_platforms_json,v.compatible_platforms_json," +
            "v.sha256,v.file_manifest_json,v.skill_md FROM skill s " +
            "JOIN skill_publisher p ON p.id=s.publisher_id " +
            "JOIN skill_version v ON v.id=s.published_version_id ";

    @Select("<script>" + MARKET_COLUMNS + "WHERE s.status='PUBLISHED' " +
            "<if test='keyword != null and keyword != \"\"'>AND (LOWER(s.display_name) LIKE CONCAT('%',LOWER(#{keyword}),'%') OR LOWER(s.summary) LIKE CONCAT('%',LOWER(#{keyword}),'%') OR LOWER(s.tags_json) LIKE CONCAT('%',LOWER(#{keyword}),'%')) </if>" +
            "<if test='category != null and category != \"\"'>AND s.category=#{category} </if>" +
            "<if test='platform != null and platform != \"\"'>AND v.compatible_platforms_json LIKE CONCAT('%',#{platform},'%') </if>" +
            "ORDER BY CASE WHEN #{sort}='downloads' THEN s.download_count END DESC," +
            "CASE WHEN #{sort}='favorites' THEN s.favorite_count END DESC,s.updated_at DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<SkillMarketRow> market(@Param("keyword") String keyword, @Param("category") String category,
            @Param("platform") String platform, @Param("sort") String sort, @Param("limit") int limit,
            @Param("offset") int offset);

    @Select("<script>SELECT COUNT(*) FROM skill s JOIN skill_version v ON v.id=s.published_version_id WHERE s.status='PUBLISHED' " +
            "<if test='keyword != null and keyword != \"\"'>AND (LOWER(s.display_name) LIKE CONCAT('%',LOWER(#{keyword}),'%') OR LOWER(s.summary) LIKE CONCAT('%',LOWER(#{keyword}),'%') OR LOWER(s.tags_json) LIKE CONCAT('%',LOWER(#{keyword}),'%')) </if>" +
            "<if test='category != null and category != \"\"'>AND s.category=#{category} </if>" +
            "<if test='platform != null and platform != \"\"'>AND v.compatible_platforms_json LIKE CONCAT('%',#{platform},'%') </if></script>")
    long countMarket(@Param("keyword") String keyword, @Param("category") String category,
            @Param("platform") String platform);

    @Select(MARKET_COLUMNS + "WHERE s.status='PUBLISHED' AND p.handle=#{handle} AND s.slug=#{slug}")
    SkillMarketRow findPublic(String handle, String slug);

    @Select("SELECT s.* FROM skill s JOIN skill_publisher p ON p.id=s.publisher_id WHERE p.member_id=#{memberId} ORDER BY s.updated_at DESC")
    List<SkillEntity> findMine(String memberId);

    @Select("SELECT s.* FROM skill s JOIN skill_publisher p ON p.id=s.publisher_id WHERE s.id=#{id} AND p.member_id=#{memberId}")
    SkillEntity findOwned(String id, String memberId);

    @Update("UPDATE skill SET download_count=download_count+1 WHERE id=#{id}")
    int incrementDownloads(String id);
    @Update("UPDATE skill SET favorite_count=favorite_count+#{delta} WHERE id=#{id} AND favorite_count+#{delta} >= 0")
    int changeFavorites(String id, int delta);
}
