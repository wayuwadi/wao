package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.SharedChatMessageReadEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
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

    /**
     * 一次写入页面实际展示范围内的全部已读事实；唯一键继续保证重复上报幂等。
     *
     * @param rows 待写入的已读事实，调用方保证非空且不超过聊天列表上限
     * @return 实际插入行数；已经存在的事实不计入
     */
    @Insert({
            "<script>",
            "INSERT IGNORE INTO shared_chat_message_read",
            "(id, message_id, reader_key, member_id, source, read_at) VALUES",
            "<foreach collection='rows' item='row' separator=','>",
            "(#{row.id}, #{row.messageId}, #{row.readerKey}, #{row.memberId}, #{row.source}, #{row.readAt})",
            "</foreach>",
            "</script>"
    })
    int insertBatchIgnore(@Param("rows") List<SharedChatMessageReadEntity> rows);
}
