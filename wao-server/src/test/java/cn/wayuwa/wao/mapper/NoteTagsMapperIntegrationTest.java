package cn.wayuwa.wao.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import cn.wayuwa.wao.domain.DocEntity;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:notetagspersistence;DB_CLOSE_DELAY=-1;MODE=MySQL")
class NoteTagsMapperIntegrationTest {
    @Autowired private NoteTagMapper tags;
    @Autowired private DocMapper docs;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void tagDictionaryAndRevisionWriteArePersistedAndStaleWriteIsRejected() {
        jdbc.execute("CREATE TABLE note_tag (name VARCHAR(32) PRIMARY KEY)");
        tags.ensureName("灵感");
        tags.ensureName("灵感");
        assertThat(tags.listNames()).containsExactly("灵感");
        jdbc.execute("""
                CREATE TABLE doc (id VARCHAR(32) PRIMARY KEY, folder_id VARCHAR(32), title VARCHAR(256),
                blocks_json CLOB, content_json CLOB, content_markdown CLOB, content_version INT,
                links_json CLOB, tags_json CLOB, system_generated BOOLEAN DEFAULT FALSE, rev INT, updated_at TIMESTAMP, deleted_at TIMESTAMP)
                """);
        jdbc.update("INSERT INTO doc (id, title, tags_json, rev) VALUES ('n1', '原文', '[]', 1)");
        DocEntity doc = new DocEntity();
        doc.setId("n1"); doc.setTitle("正文和标签一起保存"); doc.setTagsJson("[\"灵感\"]");
        doc.setRev(2); doc.setUpdatedAt(LocalDateTime.now());
        doc.setSystemGenerated(true);
        assertThat(docs.updateIfRevision(doc, 1)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT tags_json FROM doc WHERE id='n1'", String.class)).isEqualTo("[\"灵感\"]");
        assertThat(jdbc.queryForObject("SELECT system_generated FROM doc WHERE id='n1'", Boolean.class)).isTrue();
        doc.setTagsJson("[]");
        assertThat(docs.updateIfRevision(doc, 1)).isZero();
        assertThat(jdbc.queryForObject("SELECT tags_json FROM doc WHERE id='n1'", String.class)).isEqualTo("[\"灵感\"]");
    }
}
