package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.MemberEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 成员表 Mapper。 */
@Mapper
public interface MemberMapper extends BaseMapper<MemberEntity> {
    /** 目录归档不影响按主键解析历史作者；新增账号默认进入目录。 */
    @org.apache.ibatis.annotations.Select("SELECT id, name, initial, color, role FROM member WHERE directory_visible = 1 ORDER BY id")
    java.util.List<MemberEntity> directory();

}
