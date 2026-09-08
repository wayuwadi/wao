package cn.wayuwa.wao.mapper;
import cn.wayuwa.wao.domain.FeishuReactionGrantEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
@Mapper
public interface FeishuReactionGrantMapper extends BaseMapper<FeishuReactionGrantEntity> {
    @Select("SELECT * FROM feishu_reaction_grant WHERE id=#{id} FOR UPDATE")
    FeishuReactionGrantEntity lockById(@Param("id") String id);
    @Insert("""
        INSERT INTO feishu_reaction_grant (id,app_id,open_id,display_name,access_cipher,access_nonce,refresh_cipher,refresh_nonce,expires_at,refresh_expires_at)
        VALUES (#{id},#{appId},#{openId},#{displayName},#{accessCipher},#{accessNonce},#{refreshCipher},#{refreshNonce},#{expiresAt},#{refreshExpiresAt})
        ON DUPLICATE KEY UPDATE display_name=VALUES(display_name),access_cipher=VALUES(access_cipher),access_nonce=VALUES(access_nonce),
          refresh_cipher=VALUES(refresh_cipher),refresh_nonce=VALUES(refresh_nonce),expires_at=VALUES(expires_at),refresh_expires_at=VALUES(refresh_expires_at)
        """)
    int saveGrant(FeishuReactionGrantEntity grant);
}
