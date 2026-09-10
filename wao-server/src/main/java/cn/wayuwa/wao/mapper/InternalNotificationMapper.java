package cn.wayuwa.wao.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 系统内部通知持久化，不调用外部群机器人。 */
@Mapper
public interface InternalNotificationMapper {
    @Insert("""
            INSERT INTO internal_notification (id, member_id, title, body, target_path, created_at)
            VALUES (#{id}, #{memberId}, #{title}, #{body}, #{target}, NOW(6))
            """)
    int insert(@Param("id") String id, @Param("memberId") String memberId, @Param("title") String title,
            @Param("body") String body, @Param("target") String target);
}
