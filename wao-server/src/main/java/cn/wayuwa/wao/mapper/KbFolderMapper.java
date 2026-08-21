package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.KbFolderEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 知识库文件夹表 Mapper。 */
@Mapper
public interface KbFolderMapper extends BaseMapper<KbFolderEntity> {

    /** 显式恢复文件夹，同时清除随父级删除标记。 */
    @Update("""
            UPDATE kb_folder SET deleted_at = NULL, cascade_del = 0
             WHERE id = #{id} AND deleted_at IS NOT NULL
            """)
    int clearDeleted(@Param("id") String id);
}
