package cn.wayuwa.wao.mapper;

import java.util.List;
import cn.wayuwa.wao.domain.FileBlobEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FileBlobMapper extends BaseMapper<FileBlobEntity> {
    @Select("SELECT id, name, content_type, byte_size, created_by, created_at FROM file_blob ORDER BY created_at DESC")
    List<FileBlobEntity> listMeta();
}
