package cn.wayuwa.wao.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 用真实 SQL 验证知识库回收站恢复，防止 NULL 被 ORM 更新策略忽略。 */
@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:knowledgetrashrestore;DB_CLOSE_DELAY=-1;MODE=MySQL")
class KnowledgeTrashMapperIntegrationTest {

    @Autowired private KbMapper kbMapper;
    @Autowired private KbFolderMapper folderMapper;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void recreateTables() {
        jdbc.execute("DROP TABLE IF EXISTS kb_folder");
        jdbc.execute("DROP TABLE IF EXISTS kb");
        jdbc.execute("""
                CREATE TABLE kb (
                    id VARCHAR(32) PRIMARY KEY,
                    name VARCHAR(128) NOT NULL,
                    project_id VARCHAR(32),
                    deleted_at TIMESTAMP NULL,
                    deleted_by VARCHAR(32) NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE kb_folder (
                    id VARCHAR(32) PRIMARY KEY,
                    kb_id VARCHAR(32) NOT NULL,
                    name VARCHAR(128) NOT NULL,
                    deleted_at TIMESTAMP NULL,
                    cascade_del TINYINT NOT NULL DEFAULT 0
                )
                """);
    }

    @Test
    void clearingKnowledgeTrashMetadataPreservesTheProjectLink() {
        LocalDateTime deletedAt = LocalDateTime.of(2026, 8, 21, 9, 30);
        jdbc.update("""
                INSERT INTO kb (id, name, project_id, deleted_at, deleted_by)
                VALUES (?, ?, ?, ?, ?)
                """, "kb-project", "WAO 项目知识库", "p1", deletedAt, "wg");

        assertThat(kbMapper.clearDeleted("kb-project")).isEqualTo(1);

        Map<String, Object> restored = jdbc.queryForMap(
                "SELECT project_id, deleted_at, deleted_by FROM kb WHERE id = ?", "kb-project");
        assertThat(restored)
                .containsEntry("PROJECT_ID", "p1")
                .containsEntry("DELETED_AT", null)
                .containsEntry("DELETED_BY", null);
    }

    @Test
    void clearingFolderTrashMetadataAlsoClearsTheCascadeMarker() {
        LocalDateTime deletedAt = LocalDateTime.of(2026, 8, 21, 9, 30);
        jdbc.update("""
                INSERT INTO kb_folder (id, kb_id, name, deleted_at, cascade_del)
                VALUES (?, ?, ?, ?, ?)
                """, "f-project", "kb-project", "文档", deletedAt, 1);

        assertThat(folderMapper.clearDeleted("f-project")).isEqualTo(1);

        Map<String, Object> restored = jdbc.queryForMap(
                "SELECT deleted_at, cascade_del FROM kb_folder WHERE id = ?", "f-project");
        assertThat(restored)
                .containsEntry("DELETED_AT", null)
                .containsEntry("CASCADE_DEL", 0);
    }
}
