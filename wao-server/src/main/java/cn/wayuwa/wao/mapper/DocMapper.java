package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.DocEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 文档表 Mapper。 */
@Mapper
public interface DocMapper extends BaseMapper<DocEntity> {
}
