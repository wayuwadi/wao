package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.VocabularyEntryEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 个人单词本 Mapper；所有读写都必须带成员归属条件。 */
@Mapper
public interface VocabularyEntryMapper extends BaseMapper<VocabularyEntryEntity> {

    @Select("""
            SELECT * FROM vocabulary_entry
            WHERE owner_member_id = #{ownerMemberId}
            ORDER BY updated_at DESC, id DESC
            """)
    List<VocabularyEntryEntity> listOwned(@Param("ownerMemberId") String ownerMemberId);

    @Select("SELECT * FROM vocabulary_entry WHERE id = #{id} AND owner_member_id = #{ownerMemberId} LIMIT 1")
    VocabularyEntryEntity findOwned(@Param("id") String id, @Param("ownerMemberId") String ownerMemberId);

    @Select("""
            SELECT * FROM vocabulary_entry
            WHERE owner_member_id = #{ownerMemberId} AND normalized_term = #{normalizedTerm}
            LIMIT 1
            """)
    VocabularyEntryEntity findByNormalizedTerm(
            @Param("ownerMemberId") String ownerMemberId, @Param("normalizedTerm") String normalizedTerm);

    @Insert("""
            INSERT INTO vocabulary_entry (
              id, owner_member_id, term, normalized_term, meaning_text, context_text, learning_state,
              encounter_count, review_count, last_encountered_at, created_at, updated_at
            ) VALUES (
              #{id}, #{ownerMemberId}, #{term}, #{normalizedTerm}, '', '', 'LEARNING',
              1, 0, #{now}, #{now}, #{now}
            )
            ON DUPLICATE KEY UPDATE
              encounter_count = encounter_count + 1,
              last_encountered_at = VALUES(last_encountered_at),
              updated_at = VALUES(updated_at)
            """)
    int capture(@Param("id") String id, @Param("ownerMemberId") String ownerMemberId,
            @Param("term") String term, @Param("normalizedTerm") String normalizedTerm,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE vocabulary_entry
            SET meaning_text = #{meaning}, context_text = #{context}, learning_state = #{state},
                updated_at = #{updatedAt}
            WHERE id = #{id} AND owner_member_id = #{ownerMemberId}
            """)
    int updateOwned(@Param("id") String id, @Param("ownerMemberId") String ownerMemberId,
            @Param("meaning") String meaning, @Param("context") String context,
            @Param("state") String state, @Param("updatedAt") LocalDateTime updatedAt);

    @Update("""
            UPDATE vocabulary_entry
            SET review_count = review_count + 1, learning_state = #{state},
                last_reviewed_at = #{reviewedAt}, updated_at = #{reviewedAt}
            WHERE id = #{id} AND owner_member_id = #{ownerMemberId}
            """)
    int reviewOwned(@Param("id") String id, @Param("ownerMemberId") String ownerMemberId,
            @Param("state") String state, @Param("reviewedAt") LocalDateTime reviewedAt);

    @Delete("DELETE FROM vocabulary_entry WHERE id = #{id} AND owner_member_id = #{ownerMemberId}")
    int deleteOwned(@Param("id") String id, @Param("ownerMemberId") String ownerMemberId);
}
