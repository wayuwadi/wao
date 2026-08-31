package cn.wayuwa.wao.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FunctionIterationMapper {
    @Insert("""
            INSERT IGNORE INTO function_iteration_event(run_id, feature_id, commit_sha, commit_subject)
            VALUES(#{runId}, #{featureId}, #{commitSha}, #{subject})
            """)
    int insertIgnore(@Param("runId") String runId, @Param("featureId") String featureId,
            @Param("commitSha") String commitSha, @Param("subject") String subject);
}
