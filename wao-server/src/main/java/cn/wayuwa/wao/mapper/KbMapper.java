package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.KbEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 知识库表 Mapper。 */
@Mapper
public interface KbMapper extends BaseMapper<KbEntity> {

    /** 串行化同一知识库下的自动归档 upsert，避免跨节点创建同名文档。 */
    @Select("SELECT * FROM kb WHERE id = #{id} FOR UPDATE")
    KbEntity selectByIdForUpdate(@Param("id") String id);
}
