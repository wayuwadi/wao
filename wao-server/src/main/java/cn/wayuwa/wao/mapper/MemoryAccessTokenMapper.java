package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.MemoryAccessTokenEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 共享记忆 MCP 访问令牌 Mapper。 */
@Mapper
public interface MemoryAccessTokenMapper extends BaseMapper<MemoryAccessTokenEntity> {
}
