package cn.wayuwa.wao.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 为飞书归档快照签发跨节点单调递增版本。 */
@Mapper
public interface FeishuChatArchiveSequenceMapper {

    /** 锁住全局序列行，调用方必须处于事务中。 */
    @Select("SELECT next_value FROM feishu_chat_archive_sequence WHERE id = 1 FOR UPDATE")
    Long selectForUpdate();

    /** 保存已签发的下一个版本。 */
    @Update("UPDATE feishu_chat_archive_sequence SET next_value = #{value} WHERE id = 1")
    int updateValue(long value);
}
