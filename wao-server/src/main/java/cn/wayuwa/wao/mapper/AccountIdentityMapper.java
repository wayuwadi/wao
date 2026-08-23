package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.AccountIdentityEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 登录身份 Mapper。 */
@Mapper
public interface AccountIdentityMapper extends BaseMapper<AccountIdentityEntity> {
    @Select("SELECT * FROM account_identity WHERE provider = #{provider} AND provider_subject = #{subject} LIMIT 1")
    AccountIdentityEntity findByProviderSubject(@Param("provider") String provider, @Param("subject") String subject);

    @Select("SELECT * FROM account_identity WHERE provider = #{provider} "
            + "AND provider_union_subject = #{subject} LIMIT 1")
    AccountIdentityEntity findByProviderUnionSubject(@Param("provider") String provider,
            @Param("subject") String subject);

    @Select("SELECT * FROM account_identity WHERE account_id = #{accountId} ORDER BY created_at")
    List<AccountIdentityEntity> findByAccountId(@Param("accountId") String accountId);

    @Select("SELECT * FROM account_identity WHERE account_id = #{accountId} AND provider = #{provider} LIMIT 1")
    AccountIdentityEntity findByAccountProvider(@Param("accountId") String accountId, @Param("provider") String provider);
}
