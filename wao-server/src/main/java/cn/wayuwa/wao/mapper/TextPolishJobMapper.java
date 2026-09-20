package cn.wayuwa.wao.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.*;

/** 润色任务归属与幂等写入；成员锁只在入队事务内持有。 */
@Mapper
public interface TextPolishJobMapper {
    @Select("SELECT id FROM member WHERE id = #{memberId} FOR UPDATE")
    String lockMember(String memberId);

    @Select("SELECT id, member_id, kind, original_text, prompt_snapshot, ai_run_id, created_at "
            + "FROM text_polish_job WHERE id = #{id} AND member_id = #{memberId} FOR UPDATE")
    Job findOwned(@Param("id") String id, @Param("memberId") String memberId);

    @Insert("INSERT INTO text_polish_job (id, member_id, kind, original_text, prompt_snapshot, ai_run_id) "
            + "VALUES (#{id}, #{memberId}, #{kind}, #{originalText}, #{promptSnapshot}, #{aiRunId})")
    void insert(Job job);

    record Job(String id, String memberId, String kind, String originalText,
               String promptSnapshot, String aiRunId, LocalDateTime createdAt) { }
}
