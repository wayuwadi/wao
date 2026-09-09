package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.TianrenDailyReadingEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** “天人一卦”成员日存档 Mapper。 */
@Mapper
public interface TianrenDailyReadingMapper extends BaseMapper<TianrenDailyReadingEntity> {
    /** 以稳定成员行串行化同一成员的起卦与解读入队，避免双节点或手动操作竞争。 */
    @Select("SELECT id FROM member WHERE id = #{memberId} FOR UPDATE")
    String lockMember(String memberId);
}
