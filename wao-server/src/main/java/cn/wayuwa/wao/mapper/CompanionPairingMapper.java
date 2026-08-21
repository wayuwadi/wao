package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.CompanionPairingEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CompanionPairingMapper extends BaseMapper<CompanionPairingEntity> {
    @Select("SELECT * FROM companion_pairing WHERE id = #{id} FOR UPDATE")
    CompanionPairingEntity selectByIdForUpdate(@Param("id") String id);

    @Select("SELECT * FROM companion_pairing WHERE code_hash = #{codeHash} FOR UPDATE")
    CompanionPairingEntity selectByCodeForUpdate(@Param("codeHash") String codeHash);
}
