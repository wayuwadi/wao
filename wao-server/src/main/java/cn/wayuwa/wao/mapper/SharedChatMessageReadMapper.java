package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.SharedChatMessageReadEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 聊天阅读事实写入；数据库唯一键保证双节点与事件重推幂等。 */
@Mapper
public interface SharedChatMessageReadMapper extends BaseMapper<SharedChatMessageReadEntity> {

    @Insert("""
            INSERT IGNORE INTO shared_chat_message_read
                (id, message_id, reader_key, member_id, source, read_at)
            VALUES (#{id}, #{messageId}, #{readerKey}, #{memberId}, #{source}, #{readAt})
            """)
    int insertIgnore(@Param("id") String id, @Param("messageId") String messageId,
            @Param("readerKey") String readerKey, @Param("memberId") String memberId,
            @Param("source") String source, @Param("readAt") LocalDateTime readAt);
}
