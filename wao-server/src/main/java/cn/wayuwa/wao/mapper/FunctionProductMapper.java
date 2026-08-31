package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.FunctionProductEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface FunctionProductMapper extends BaseMapper<FunctionProductEntity> {
    @Insert("""
            INSERT INTO function_product(feature_id) VALUES(#{featureId})
            ON DUPLICATE KEY UPDATE feature_id = VALUES(feature_id)
            """)
    int ensure(@Param("featureId") String featureId);

    @Update("""
            UPDATE function_product
            SET manual_adjustment = GREATEST(-100, LEAST(99, manual_adjustment + #{delta}))
            WHERE feature_id = #{featureId}
            """)
    int adjust(@Param("featureId") String featureId, @Param("delta") int delta);

    @Update("UPDATE function_product SET iteration_count = iteration_count + 1 WHERE feature_id = #{featureId}")
    int incrementIteration(@Param("featureId") String featureId);
}
