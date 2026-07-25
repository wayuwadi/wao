package cn.wayuwa.wao.mapper;

import java.util.List;

import cn.wayuwa.wao.domain.ImageBlobEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** 图床 Mapper。 */
@Mapper
public interface ImageBlobMapper extends BaseMapper<ImageBlobEntity> {

    /** 列出元数据，不含 {@code content}——图床列表不该把每张图的正文都读进内存。 */
    @Select("SELECT id, name, content_type, byte_size, source, created_at "
            + "FROM image_blob ORDER BY created_at DESC")
    List<ImageBlobEntity> listMeta();
}
