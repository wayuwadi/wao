package cn.wayuwa.wao.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

/** 仅连接专用 3318 端口上的新建合成数据库，不读取或修改生产数据。 */
@EnabledIfSystemProperty(named="wao.share.mysql-test",matches="true")
class DocumentShareMigrationMysqlTest {
    @Test void migrationScansDocumentsOnceAndKeepsPrivateReferencesAfterRemoval() throws Exception {
        var db=new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:mysql://127.0.0.1:3318/wao_share_test_migration?useSSL=false&characterEncoding=UTF-8","root",""));
        db.execute("CREATE TABLE private_note_asset(asset_type VARCHAR(8),asset_id VARCHAR(64),PRIMARY KEY(asset_type,asset_id))");
        db.execute("CREATE TABLE image_blob(id VARCHAR(64) PRIMARY KEY,source VARCHAR(160))");
        db.execute("CREATE TABLE file_blob(id VARCHAR(64) PRIMARY KEY)");
        db.execute("CREATE TABLE doc(content_markdown LONGTEXT,content_json LONGTEXT,blocks_json LONGTEXT)");
        var imageRows=new ArrayList<Object[]>();
        for(int i=0;i<5000;i++) imageRows.add(new Object[]{"image-"+i,"测试上传"});
        db.batchUpdate("INSERT INTO image_blob VALUES (?,?)",imageRows);
        db.update("INSERT INTO image_blob VALUES ('draft','文档 · 草稿'),('doc-front','任务 · 不应公开授权')");
        db.update("INSERT INTO file_blob VALUES ('doc-file'),('other-file')");
        String body="doc-front/raw \"引号\"\\反斜杠\n"+"测试正文".repeat(1125)
                +"\n![图](/api/images/image-0/raw) ![另图](https://test.example/api/images/image-4999/preview)";
        var documents=new ArrayList<Object[]>();
        for(int i=0;i<600;i++) documents.add(new Object[]{body,
                "{\"src\":\"/api/images/image-3/raw\"}","[附件](/api/files/doc-file/download)"});
        db.batchUpdate("INSERT INTO doc VALUES (?,?,?)",documents);
        String migration=Files.readString(Path.of("src/main/resources/db/migration/V195__document_share_asset_privacy.sql"));
        assertTimeout(Duration.ofSeconds(15),()->{
            for(String statement:migration.split(";")) if(!statement.isBlank()) db.execute(statement);
        });
        db.update("DELETE FROM doc");
        assertThat(db.queryForList("SELECT asset_id FROM private_note_asset ORDER BY asset_id",String.class))
                .containsExactly("doc-file","draft","image-0","image-3","image-4999");
        for(String statement:migration.split(";")) if(!statement.isBlank()) db.execute(statement);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM private_note_asset",Integer.class)).isEqualTo(5);
    }
}
