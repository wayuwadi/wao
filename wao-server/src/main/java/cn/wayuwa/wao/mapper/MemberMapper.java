package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.MemberEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 成员表 Mapper。 */
@Mapper
public interface MemberMapper extends BaseMapper<MemberEntity> {
}
