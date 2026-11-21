package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.VaultGroupEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/** 密钥库分组表 Mapper。 */
@Mapper
public interface VaultGroupMapper extends BaseMapper<VaultGroupEntity> {
    @Insert("""
            INSERT INTO vault_group (id, name, ord, owner_member_id, is_default)
            VALUES (#{id}, #{name}, #{ord}, #{ownerMemberId}, #{isDefault})
            ON DUPLICATE KEY UPDATE id = id
            """)
    int ensureDefault(VaultGroupEntity group);
}
