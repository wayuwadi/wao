package cn.wayuwa.wao.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.LocalDateTime;
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
        jdbc.execute("DROP TABLE IF EXISTS kb");
        jdbc.execute("DROP TABLE IF EXISTS drawing_group_assignment");
        jdbc.execute("DROP TABLE IF EXISTS drawing_group");
        jdbc.execute("CREATE TABLE doc(id VARCHAR(32) PRIMARY KEY, kb_id VARCHAR(32), title VARCHAR(256), drawing_id VARCHAR(32), deleted_at TIMESTAMP, archived_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE kb(id VARCHAR(32) PRIMARY KEY, deleted_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE drawing_file(id VARCHAR(32) PRIMARY KEY, owner_member_id VARCHAR(32), title VARCHAR(128), scene_json TEXT, revision INT, created_at TIMESTAMP, updated_at TIMESTAMP, knowledge_doc_id VARCHAR(32), deleted_at TIMESTAMP, FOREIGN KEY(knowledge_doc_id) REFERENCES doc(id) ON DELETE CASCADE)");
        jdbc.execute("CREATE TABLE drawing_group(id VARCHAR(32), member_id VARCHAR(32), name VARCHAR(128))");
        jdbc.execute("CREATE TABLE drawing_group_assignment(file_id VARCHAR(32), member_id VARCHAR(32), group_id VARCHAR(32))");
        jdbc.update("INSERT INTO doc(id,kb_id,title,drawing_id) VALUES('d1','k1','知识库画板','dr1')");
        jdbc.update("INSERT INTO kb(id) VALUES('k1')");
        jdbc.update("INSERT INTO drawing_file VALUES('dr1','m1','旧标题','{}',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'d1',NULL)");
        jdbc.update("INSERT INTO drawing_file VALUES('dr2','m2','个人草图','{}',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,NULL,NULL)");
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

    @Test void knownRevisionOmitsSceneWhileKeepingCurrentMetadataAndAccessFilters() {
        assertThat(drawings.findOwnedAtRevision("dr2", "m2", 1).getSceneJson()).isNull();
        assertThat(drawings.findOwnedAtRevision("dr2", "m1", 1)).isNull();
        assertThat(drawings.findOwnedAtRevision("dr2", "m2", 2).getSceneJson()).isEqualTo("{}");
        jdbc.update("UPDATE doc SET title='新标题' WHERE id='d1'");
        var linked = drawings.findKnowledgeAtRevision("dr1", 1);
        assertThat(linked.getTitle()).isEqualTo("新标题");
        assertThat(linked.getSceneJson()).isNull();
        assertThat(drawings.findOwnedAtRevision("dr1", "m1", 1).getSceneJson()).isNull();
        assertThat(drawings.findKnowledgeAtRevision("dr1", 0).getSceneJson()).isEqualTo("{}");
        jdbc.update("UPDATE doc SET deleted_at=CURRENT_TIMESTAMP WHERE id='d1'");
        assertThat(drawings.findOwnedAtRevision("dr1", "m1", 1)).isNull();
        assertThat(drawings.findKnowledgeAtRevision("dr1", 1)).isNull();
    }

    @Test void independentTrashChecksOwnerAndResolvesRestoreAgainstExactExpiry() {
        var deletedAt = LocalDateTime.of(2026, 11, 24, 9, 0);
        jdbc.update("INSERT INTO drawing_group VALUES('g1','m2','临时分组')");
        jdbc.update("INSERT INTO drawing_group_assignment VALUES('dr2','m2','g1')");
        assertThat(drawings.moveToTrash("dr1", "m1", deletedAt)).isZero();
        assertThat(drawings.moveToTrash("dr2", "m1", deletedAt)).isZero();
        assertThat(drawings.moveToTrash("dr2", "m2", deletedAt)).isOne();
        assertThat(drawings.moveToTrash("dr2", "m2", deletedAt.plusHours(1))).isZero();
        assertThat(drawings.listOwned("m2")).isEmpty();
        assertThat(drawings.findOwned("dr2", "m2")).isNull();
        assertThat(drawings.saveScene("dr2", "m2", "{}", 1, deletedAt)).isZero();
        assertThat(drawings.listTrash("m1", deletedAt.minusSeconds(1))).isEmpty();
        assertThat(drawings.listTrash("m2", deletedAt.minusSeconds(1))).singleElement().satisfies(entry -> {
            assertThat(entry.groupName()).isEqualTo("临时分组");
            assertThat(entry.deletedAt()).isEqualTo(deletedAt);
        });
        assertThat(drawings.restoreTrash("dr2", "m1", deletedAt.minusSeconds(1))).isZero();
        assertThat(drawings.purgeTrash(deletedAt.minusSeconds(1))).isZero();
        assertThat(drawings.restoreTrash("dr2", "m2", deletedAt.minusSeconds(1))).isOne();
        assertThat(drawings.findOwned("dr2", "m2").getRevision()).isEqualTo(1);

        assertThat(drawings.moveToTrash("dr2", "m2", deletedAt)).isOne();
        assertThat(drawings.listTrash("m2", deletedAt)).isEmpty();
        assertThat(drawings.restoreTrash("dr2", "m2", deletedAt)).isZero();
        assertThat(drawings.purgeTrash(deletedAt)).isOne();
        assertThat(drawings.listOwned("m1")).hasSize(1);
    }
}
