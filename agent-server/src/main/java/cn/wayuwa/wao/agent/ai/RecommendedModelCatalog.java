package cn.wayuwa.wao.agent.ai;

import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 只读取 WAO 集中模型的非敏感元数据，供 Agent 判断推荐模型是否可用。
 *
 * <p>API Key 仍只由 wao-server 解密和使用，本组件不会读取 credential_json。
 */
@Component
public class RecommendedModelCatalog {
    private final JdbcTemplate jdbc;

    public RecommendedModelCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 返回最新启用的受信 Chat Completions 模型；表尚未就绪时如实返回空。 */
    public ModelMetadata current() {
        try {
            List<ModelMetadata> rows = jdbc.query("""
                    SELECT provider, model
                    FROM ai_account
                    WHERE enabled = 1
                      AND auth_method = 'api_key'
                      AND provider IN ('deepseek', 'model_gateway')
                    ORDER BY created_at DESC
                    LIMIT 1
                    """, (rs, rowNum) -> new ModelMetadata(rs.getString("provider"), rs.getString("model")));
            return rows.stream().filter(ModelMetadata::valid).findFirst().orElse(null);
        } catch (DataAccessException ignored) {
            return null;
        }
    }

    public record ModelMetadata(String provider, String model) {
        boolean valid() {
            return StringUtils.hasText(provider) && StringUtils.hasText(model);
        }

        public String displayProvider() {
            return "model_gateway".equals(provider) ? "WAO 推荐模型" : "DeepSeek";
        }
    }
}
