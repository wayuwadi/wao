package cn.wayuwa.wao.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class NoteAssetPrivacyServiceTest {
    @Test void migrationProtectsOldDocumentAssetsBeforeTheirFirstAnonymousRequest() throws Exception {
        var db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        db.execute("CREATE TABLE private_note_asset(asset_type VARCHAR(8),asset_id VARCHAR(64),PRIMARY KEY(asset_type,asset_id))");
        db.execute("CREATE TABLE image_blob(id VARCHAR(64),source VARCHAR(160))");
        db.execute("CREATE TABLE file_blob(id VARCHAR(64))");
        db.execute("CREATE TABLE doc(content_markdown LONGTEXT,content_json LONGTEXT,blocks_json LONGTEXT)");
        db.update("INSERT INTO image_blob VALUES ('doc-draft','文档 · 草稿'),('doc-saved','历史上传'),('task','任务 · 可公开')");
        db.update("INSERT INTO file_blob VALUES ('doc-file'),('other-file')");
        db.update("INSERT INTO doc(content_markdown) VALUES ('![图](/api/images/doc-saved/raw) [文件](/api/files/doc-file/download)')");
        String migration=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/db/migration/V195__document_share_asset_privacy.sql"));
        for(String statement:migration.split(";")) if(!statement.isBlank()) db.execute(statement);
        db.update("DELETE FROM doc");
        assertThat(db.queryForList("SELECT asset_id FROM private_note_asset ORDER BY asset_id",String.class))
                .containsExactly("doc-draft","doc-file","doc-saved");
    }

    @Test void draftUploadsOriginalsAndSavedReferencesStayPrivateAfterRemoval() {
        var db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        db.execute("CREATE TABLE private_note_asset(asset_type VARCHAR(8),asset_id VARCHAR(64),PRIMARY KEY(asset_type,asset_id))");
        db.execute("CREATE TABLE image_blob(id VARCHAR(64),source VARCHAR(160))");
        db.execute("CREATE TABLE note_source_asset_ref(asset_type VARCHAR(8),asset_id VARCHAR(64))");
        db.execute("CREATE TABLE note_ai_workflow(image_run_id VARCHAR(32))");
        db.execute("CREATE TABLE note_ai_transformation(task_id VARCHAR(32))");
        db.execute("CREATE TABLE doc(kb_id VARCHAR(32),content_markdown LONGTEXT,content_json LONGTEXT,blocks_json LONGTEXT)");
        db.update("INSERT INTO image_blob VALUES ('draft','小记 · 草稿'),('doc-draft','文档 · 草稿'),('task','任务 · 可公开')");
        db.update("INSERT INTO note_source_asset_ref VALUES ('files','original-file')");
        db.update("INSERT INTO doc(kb_id,content_markdown) VALUES ('kb-notes','![配图](/api/images/saved/raw)')");
        db.update("INSERT INTO doc(kb_id,content_markdown) VALUES ('knowledge','![配图](/api/images/doc-image/raw) [附件](/api/files/doc-file/download)')");
        var privacy=new NoteAssetPrivacyService(db,new ObjectMapper());
        assertThat(privacy.isPrivate("images","draft")).isTrue();
        assertThat(privacy.isPrivate("files","original-file")).isTrue();
        assertThat(privacy.isPrivate("images","saved")).isTrue();
        assertThat(privacy.isPrivate("images","task")).isFalse();
        assertThat(privacy.isPrivate("images","doc-draft")).isTrue();
        assertThat(privacy.isPrivate("images","doc-image")).isTrue();
        assertThat(privacy.isPrivate("files","doc-file")).isTrue();
        db.update("DELETE FROM doc");db.update("DELETE FROM note_source_asset_ref");db.update("DELETE FROM image_blob WHERE id='draft'");
        assertThat(privacy.isPrivate("images","draft")).isTrue();
        assertThat(privacy.isPrivate("files","original-file")).isTrue();
        assertThat(privacy.isPrivate("images","saved")).isTrue();
        assertThat(privacy.isPrivate("images","doc-image")).isTrue();
        assertThat(privacy.isPrivate("files","doc-file")).isTrue();
    }
}
