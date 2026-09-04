package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.dto.AiRunAccountDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ContentGenerationQueueProcessorTest {
    private JdbcTemplate jdbc;
    private AiAccountService accounts;
    private DeepSeekApiClient model;
    private ContentGenerationQueueProcessor processor;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:content-generation-" + System.nanoTime()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE agent_publish_task (
                    id VARCHAR(36) PRIMARY KEY, topic VARCHAR(200) NOT NULL,
                    title VARCHAR(200), content CLOB, tags_json VARCHAR(1000), cover_text VARCHAR(200),
                    structure_json CLOB, status VARCHAR(32) NOT NULL, progress INT NOT NULL,
                    node_id VARCHAR(64), error_message VARCHAR(1000), generation_executor VARCHAR(24),
                    generation_system_prompt CLOB, generation_user_prompt CLOB,
                    generation_claim_token VARCHAR(64), generation_lease_until TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL)
                """);
        accounts = mock(AiAccountService.class);
        model = mock(DeepSeekApiClient.class);
        processor = new ContentGenerationQueueProcessor(jdbc, accounts, model, new ObjectMapper());
        when(accounts.defaultDocumentPolishAccount()).thenReturn(new AiRunAccountDto(
                "aa-1", "model_gateway", "推荐模型", "api_key", null, "secret-test-key",
                "https://gateway.example/v1", "deepseek-v4-pro", "chat_completions"));
    }

    @Test
    void generatedTitleBodyTagsAndBlocksArePersistedForReview() {
        insertPending("pt-success");
        when(model.complete(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn("""
                        ```json
                        {"title":"AI、Skill 与 MCP，一次讲明白","coverText":null,
                         "body":"AI 是能力，Skill 是做事方法，MCP 是连接外部工具的协议。",
                         "tags":["#AI","Codex"],
                         "blocks":[{"label":"开场","text":"先从三个层次理解。","seconds":null}]}
                        ```
                        """);

        processor.advance();

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM agent_publish_task WHERE id = 'pt-success'");
        assertThat(row.get("STATUS")).isEqualTo("WAITING_REVIEW");
        assertThat(row.get("PROGRESS")).isEqualTo(45);
        assertThat(row.get("TITLE")).isEqualTo("AI、Skill 与 MCP，一次讲明白");
        assertThat(row.get("CONTENT").toString()).contains("MCP 是连接外部工具的协议");
        assertThat(row.get("TAGS_JSON").toString()).isEqualTo("[\"AI\",\"Codex\"]");
        assertThat(row.get("STRUCTURE_JSON").toString()).contains("先从三个层次理解");
        assertThat(row.get("GENERATION_SYSTEM_PROMPT")).isNull();
        assertThat(row.get("GENERATION_USER_PROMPT")).isNull();
        assertThat(row.get("GENERATION_CLAIM_TOKEN")).isNull();
        verify(model).complete("model_gateway", "https://gateway.example/v1", "secret-test-key",
                "deepseek-v4-pro", "只返回 JSON", "话题：AI、Skill 和 MCP");
    }

    @Test
    void providerFailureIsVisibleAndKeepsPreparedRequestForRetry() {
        insertPending("pt-failed");
        when(model.complete(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("上游暂时繁忙"));

        processor.advance();

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM agent_publish_task WHERE id = 'pt-failed'");
        assertThat(row.get("STATUS")).isEqualTo("FAILED");
        assertThat(row.get("ERROR_MESSAGE").toString()).contains("生成文案失败", "上游暂时繁忙");
        assertThat(row.get("GENERATION_SYSTEM_PROMPT")).isEqualTo("只返回 JSON");
        assertThat(row.get("GENERATION_USER_PROMPT")).isEqualTo("话题：AI、Skill 和 MCP");
        assertThat(row.get("GENERATION_CLAIM_TOKEN")).isNull();
        assertThat(row.get("GENERATION_LEASE_UNTIL")).isNull();
    }

    private void insertPending(String id) {
        jdbc.update("""
                INSERT INTO agent_publish_task(
                    id, topic, status, progress, generation_executor,
                    generation_system_prompt, generation_user_prompt, updated_at)
                VALUES (?, 'AI、Skill 和 MCP', 'GENERATING', 10, 'WAO_RECOMMENDED',
                        '只返回 JSON', '话题：AI、Skill 和 MCP', ?)
                """, id, LocalDateTime.now());
    }
}
