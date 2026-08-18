package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.FamilyImportantDateEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FamilyImportantDateMapper extends BaseMapper<FamilyImportantDateEntity> {
    @Select("SELECT * FROM family_important_date WHERE id = #{id} AND member_id = #{memberId} LIMIT 1")
    FamilyImportantDateEntity findOwned(@Param("id") String id, @Param("memberId") String memberId);
}
