package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.AccountEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 账号主体 Mapper。 */
@Mapper
public interface AccountMapper extends BaseMapper<AccountEntity> {
    @Select("SELECT * FROM account WHERE member_id = #{memberId} LIMIT 1")
    AccountEntity findByMemberId(@Param("memberId") String memberId);

    @Select("""
            SELECT a.* FROM account a
            JOIN member m ON m.id = a.member_id
            WHERE m.name = #{name} AND a.status = 'ACTIVE'
              AND NOT EXISTS (SELECT 1 FROM account_identity i WHERE i.account_id = a.id)
              AND NOT EXISTS (SELECT 1 FROM password_credential p WHERE p.account_id = a.id)
            """)
    List<AccountEntity> findUnclaimedByMemberName(@Param("name") String name);

    @Select("""
            SELECT a.* FROM account a
            WHERE a.status = 'ACTIVE'
              AND NOT EXISTS (SELECT 1 FROM account_identity i WHERE i.account_id = a.id)
              AND NOT EXISTS (SELECT 1 FROM password_credential p WHERE p.account_id = a.id)
            ORDER BY a.created_at
            """)
    List<AccountEntity> findUnclaimed();
}
