package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.FeishuBotInboxEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** 持久回调队列，支持重复投递和跨进程有界重试。 */
@Mapper
public interface FeishuBotInboxMapper extends BaseMapper<FeishuBotInboxEntity> {
    @Select("SELECT * FROM feishu_bot_inbox WHERE status IN ('PENDING','PROCESSING') "
            + "AND attempts < 6 AND available_at <= NOW() ORDER BY updated_at LIMIT 10")
    List<FeishuBotInboxEntity> pending();
    @Update("UPDATE feishu_bot_inbox SET status='PROCESSING', attempts=attempts+1, "
            + "lease_token=#{leaseToken}, available_at=DATE_ADD(NOW(), INTERVAL 5 MINUTE), updated_at=NOW() "
            + "WHERE id=#{id} AND status IN ('PENDING','PROCESSING') AND attempts < 6 AND available_at <= NOW()")
    int claim(@Param("id") String id, @Param("leaseToken") String leaseToken);
    @Select("SELECT lease_token FROM feishu_bot_inbox WHERE id=#{id} AND status='PROCESSING' FOR UPDATE")
    String lockLease(@Param("id") String id);
    @Update("UPDATE feishu_bot_inbox SET status='DONE', payload_ciphertext=NULL, payload_nonce=NULL, "
            + "lease_token=NULL, error_code=NULL, updated_at=NOW() WHERE id=#{id} "
            + "AND status='PROCESSING' AND lease_token=#{leaseToken}")
    int complete(@Param("id") String id, @Param("leaseToken") String leaseToken);
    @Update("UPDATE feishu_bot_inbox SET status=IF(attempts>=6,'FAILED','PENDING'), "
            + "error_code='CAPTURE_FAILED', available_at=DATE_ADD(NOW(), INTERVAL 1 MINUTE), "
            + "updated_at=NOW(), lease_token=NULL WHERE id=#{id} AND status='PROCESSING' AND lease_token=#{leaseToken}")
    int retry(@Param("id") String id, @Param("leaseToken") String leaseToken);
    @Update("UPDATE feishu_bot_inbox SET status='FAILED', error_code='RETRY_EXHAUSTED', lease_token=NULL, "
            + "updated_at=NOW() WHERE status='PROCESSING' AND attempts>=6 AND available_at<=NOW()")
    int expireExhausted();
    @Delete("DELETE FROM feishu_bot_inbox WHERE status='DONE' AND updated_at < DATE_SUB(NOW(), INTERVAL 7 DAY)")
    int purgeCompleted();
}
