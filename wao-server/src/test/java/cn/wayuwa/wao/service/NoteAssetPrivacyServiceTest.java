package cn.wayuwa.wao.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class NoteAssetPrivacyServiceTest {
    @Test void draftUploadsOriginalsAndSavedReferencesStayPrivateAfterRemoval() {
        var db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        db.execute("CREATE TABLE private_note_asset(asset_type VARCHAR(8),asset_id VARCHAR(64),PRIMARY KEY(asset_type,asset_id))");
        db.execute("CREATE TABLE image_blob(id VARCHAR(64),source VARCHAR(160))");
        db.execute("CREATE TABLE note_source_asset_ref(asset_type VARCHAR(8),asset_id VARCHAR(64))");
        db.execute("CREATE TABLE note_ai_workflow(image_run_id VARCHAR(32))");
        db.execute("CREATE TABLE note_ai_transformation(task_id VARCHAR(32))");
        db.execute("CREATE TABLE doc(kb_id VARCHAR(32),content_markdown LONGTEXT,content_json LONGTEXT,blocks_json LONGTEXT)");
        db.update("INSERT INTO image_blob VALUES ('draft','小记 · 草稿'),('task','任务 · 可公开')");
        db.update("INSERT INTO note_source_asset_ref VALUES ('files','original-file')");
        db.update("INSERT INTO doc(kb_id,content_markdown) VALUES ('kb-notes','![配图](/api/images/saved/raw)')");
        var privacy=new NoteAssetPrivacyService(db,new ObjectMapper());
        assertThat(privacy.isPrivate("images","draft")).isTrue();
        assertThat(privacy.isPrivate("files","original-file")).isTrue();
        assertThat(privacy.isPrivate("images","saved")).isTrue();
        assertThat(privacy.isPrivate("images","task")).isFalse();
        db.update("DELETE FROM doc");db.update("DELETE FROM note_source_asset_ref");db.update("DELETE FROM image_blob WHERE id='draft'");
        assertThat(privacy.isPrivate("images","draft")).isTrue();
        assertThat(privacy.isPrivate("files","original-file")).isTrue();
        assertThat(privacy.isPrivate("images","saved")).isTrue();
    }
}
