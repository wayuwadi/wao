package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.SharedChatMessageEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 共享聊天消息查询；游标按数据库中的创建时间和 id 组合排序，避免同一微秒漏消息。 */
@Mapper
public interface SharedChatMessageMapper extends BaseMapper<SharedChatMessageEntity> {

    @Select("""
            SELECT * FROM shared_chat_message
             ORDER BY created_at DESC, id DESC
             LIMIT #{limit}
            """)
    List<SharedChatMessageEntity> latest(@Param("limit") int limit);

    @Select("""
            SELECT message.* FROM shared_chat_message message
              JOIN shared_chat_message anchor ON anchor.id = #{afterId}
             WHERE message.created_at > anchor.created_at
                OR (message.created_at = anchor.created_at AND message.id > anchor.id)
             ORDER BY message.created_at ASC, message.id ASC
             LIMIT #{limit}
            """)
    List<SharedChatMessageEntity> after(@Param("afterId") String afterId, @Param("limit") int limit);

    @Select("""
            SELECT * FROM shared_chat_message
             WHERE member_id = #{memberId} AND client_token = #{clientToken}
             LIMIT 1
            """)
    SharedChatMessageEntity findByClientToken(@Param("memberId") String memberId,
                                               @Param("clientToken") String clientToken);
}
