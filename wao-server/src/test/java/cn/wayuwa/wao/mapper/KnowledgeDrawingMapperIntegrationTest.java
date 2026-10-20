package cn.wayuwa.wao.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:knowledgepage;DB_CLOSE_DELAY=-1;MODE=MySQL")
class KnowledgeDrawingMapperIntegrationTest {
    @Autowired DrawingFileMapper drawings;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach void tables() {
        jdbc.execute("DROP TABLE IF EXISTS drawing_file");
        jdbc.execute("DROP TABLE IF EXISTS doc");
        jdbc.execute("CREATE TABLE doc(id VARCHAR(32) PRIMARY KEY, kb_id VARCHAR(32), title VARCHAR(256), drawing_id VARCHAR(32), deleted_at TIMESTAMP, archived_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE drawing_file(id VARCHAR(32) PRIMARY KEY, owner_member_id VARCHAR(32), title VARCHAR(128), scene_json TEXT, revision INT, created_at TIMESTAMP, updated_at TIMESTAMP, knowledge_doc_id VARCHAR(32), FOREIGN KEY(knowledge_doc_id) REFERENCES doc(id) ON DELETE CASCADE)");
        jdbc.update("INSERT INTO doc(id,kb_id,title,drawing_id) VALUES('d1','k1','知识库画板','dr1')");
        jdbc.update("INSERT INTO drawing_file VALUES('dr1','m1','旧标题','{}',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'d1')");
        jdbc.update("INSERT INTO drawing_file VALUES('dr2','m2','个人草图','{}',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL)");
    }
    @Test void bothEntrancesShareTitleAndIdentityWithoutLeakingOtherMembers() {
        var list = drawings.listOwned("m1");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).getDocId()).isEqualTo("d1");
        assertThat(list.get(0).getKbId()).isEqualTo("k1");
        jdbc.update("UPDATE doc SET title='新的名称' WHERE id='d1'");
        assertThat(drawings.findOwned("dr1", "m1").getTitle()).isEqualTo("新的名称");
        assertThat(drawings.findOwned("dr1", "m2")).isNull();
    }
    @Test void trashRestoreArchiveAndPurgeFollowKnowledgeLifecycle() {
        jdbc.update("UPDATE doc SET deleted_at=CURRENT_TIMESTAMP WHERE id='d1'");
        assertThat(drawings.listOwned("m1")).isEmpty();
        assertThat(drawings.findOwned("dr1", "m1")).isNull();
        jdbc.update("UPDATE doc SET deleted_at=NULL WHERE id='d1'");
        assertThat(drawings.listOwned("m1")).hasSize(1);
        jdbc.update("UPDATE doc SET archived_at=CURRENT_TIMESTAMP WHERE id='d1'");
        assertThat(drawings.listOwned("m1")).isEmpty();
        assertThat(drawings.findOwned("dr1", "m1")).isNotNull();
        jdbc.update("DELETE FROM doc WHERE id='d1'");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM drawing_file WHERE id='dr1'", Integer.class)).isZero();
    }
}
