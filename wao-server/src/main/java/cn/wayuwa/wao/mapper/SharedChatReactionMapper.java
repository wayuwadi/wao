package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.SharedChatReactionEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 聊天表情回复查询与按真实操作者撤销。 */
@Mapper
public interface SharedChatReactionMapper extends BaseMapper<SharedChatReactionEntity> {

    @Select("""
            SELECT * FROM shared_chat_reaction
             WHERE message_id = #{messageId} AND reactor_key = #{reactorKey} AND emoji_type = #{emojiType}
             LIMIT 1
            """)
    SharedChatReactionEntity find(@Param("messageId") String messageId,
            @Param("reactorKey") String reactorKey, @Param("emojiType") String emojiType);

    @Delete("""
            DELETE FROM shared_chat_reaction
             WHERE message_id = #{messageId} AND reactor_key = #{reactorKey} AND emoji_type = #{emojiType}
            """)
    int deleteReaction(@Param("messageId") String messageId,
            @Param("reactorKey") String reactorKey, @Param("emojiType") String emojiType);
}
