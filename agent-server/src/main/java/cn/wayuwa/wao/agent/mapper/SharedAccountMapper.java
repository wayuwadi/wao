package cn.wayuwa.wao.agent.mapper;

import cn.wayuwa.wao.agent.dto.SharedAccountDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SharedAccountMapper {
    @Select("""
            SELECT a.id AS account_id, m.id AS member_id, m.name AS display_name,
                   m.initial AS avatar_text, a.status
            FROM account a
            JOIN member m ON m.id = a.member_id
            WHERE a.id = #{accountId}
            """)
    SharedAccountDto findById(@Param("accountId") String accountId);
}
