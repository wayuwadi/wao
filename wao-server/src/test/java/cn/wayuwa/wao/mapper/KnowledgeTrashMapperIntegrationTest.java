package cn.wayuwa.wao.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 用真实 SQL 验证知识库回收站与文档归档恢复，防止 NULL 被 ORM 更新策略忽略。 */
@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:knowledgetrashrestore;DB_CLOSE_DELAY=-1;MODE=MySQL")
class KnowledgeTrashMapperIntegrationTest {

    @Autowired private KbMapper kbMapper;
    @Autowired private KbFolderMapper folderMapper;
    @Autowired private DocMapper docMapper;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void recreateTables() {
        jdbc.execute("DROP TABLE IF EXISTS doc");
        jdbc.execute("DROP TABLE IF EXISTS kb_folder");
        jdbc.execute("DROP TABLE IF EXISTS kb");
        jdbc.execute("""
                CREATE TABLE kb (directory_json VARCHAR(100000),
                    id VARCHAR(32) PRIMARY KEY,
                    name VARCHAR(128) NOT NULL,
                    project_id VARCHAR(32),
                    deleted_at TIMESTAMP NULL,
                    deleted_by VARCHAR(32) NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE doc (rev INT DEFAULT 1,
                    id VARCHAR(32) PRIMARY KEY,
                    kb_id VARCHAR(32) NOT NULL,
                    folder_id VARCHAR(32) NOT NULL,
                    title VARCHAR(256) NOT NULL,
                    deleted_at TIMESTAMP NULL,
                    archived_at TIMESTAMP NULL,
                    archived_by VARCHAR(32) NULL
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
    void directoryMovePreservesBodyAndArchiveAndInvalidatesStaleRevisions() {
        jdbc.update("INSERT INTO doc(id,kb_id,folder_id,title,rev,archived_at) VALUES ('d1','k1','f1','保留正文',7,CURRENT_TIMESTAMP)");
        assertThat(docMapper.moveDirectoryFolder("other", "d1", "f2")).isZero();
        assertThat(docMapper.moveDirectoryFolder("k1", "d1", "f2")).isEqualTo(1);
        Map<String, Object> moved = jdbc.queryForMap("SELECT title,folder_id,rev,archived_at FROM doc WHERE id='d1'");
        assertThat(moved).containsEntry("TITLE", "保留正文").containsEntry("FOLDER_ID", "f2").containsEntry("REV", 8);
        assertThat(moved.get("ARCHIVED_AT")).isNotNull();
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

    @Test
    void archivingAndRestoringPreservesTheDocumentAndItsOriginalFolder() {
        jdbc.update("INSERT INTO doc (id, kb_id, folder_id, title) VALUES (?, ?, ?, ?)",
                "d1", "k1", "f1", "一次性文章");
        LocalDateTime archivedAt = LocalDateTime.of(2026, 9, 3, 11, 30);

        assertThat(docMapper.markArchived("d1", archivedAt, "wg")).isEqualTo(1);
        assertThat(jdbc.queryForMap(
                "SELECT kb_id, folder_id, title, archived_at, archived_by, deleted_at FROM doc WHERE id = ?", "d1"))
                .containsEntry("KB_ID", "k1")
                .containsEntry("FOLDER_ID", "f1")
                .containsEntry("TITLE", "一次性文章")
                .containsEntry("ARCHIVED_BY", "wg")
                .containsEntry("DELETED_AT", null);

        assertThat(docMapper.clearArchived("d1")).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT archived_at, archived_by, folder_id FROM doc WHERE id = ?", "d1"))
                .containsEntry("ARCHIVED_AT", null)
                .containsEntry("ARCHIVED_BY", null)
                .containsEntry("FOLDER_ID", "f1");
    }
}
