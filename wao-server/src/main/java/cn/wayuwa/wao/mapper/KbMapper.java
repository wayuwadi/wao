package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.KbEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 知识库表 Mapper。 */
@Mapper
public interface KbMapper extends BaseMapper<KbEntity> {

    /** 串行化同一知识库下的自动归档 upsert，避免跨节点创建同名文档。 */
    @Select("SELECT * FROM kb WHERE id = #{id} FOR UPDATE")
    KbEntity selectByIdForUpdate(@Param("id") String id);

    /** 目录只能由关系接口写入，避免名称或权限编辑回写旧目录快照。 */
    @Update("UPDATE kb SET directory_json = #{directory} WHERE id = #{id} AND deleted_at IS NULL")
    int updateDirectory(@Param("id") String id, @Param("directory") String directory);

    /** 显式清除回收站元数据；避免 {@code updateById} 的非空字段策略忽略 NULL。 */
    @Update("""
            UPDATE kb SET deleted_at = NULL, deleted_by = NULL
             WHERE id = #{id} AND deleted_at IS NOT NULL
            """)
    int clearDeleted(@Param("id") String id);
}
