package cn.wayuwa.wao.mapper;

import java.util.List;
import cn.wayuwa.wao.domain.HostedPageEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface HostedPageMapper extends BaseMapper<HostedPageEntity> {
    @Select("SELECT id, slug, title, file_name, icon_content_type, created_by, created_at, updated_at FROM hosted_page ORDER BY updated_at DESC")
    List<HostedPageEntity> listMeta();
    @Select("SELECT * FROM hosted_page WHERE slug = #{slug} LIMIT 1")
    HostedPageEntity findBySlug(@Param("slug") String slug);
}
