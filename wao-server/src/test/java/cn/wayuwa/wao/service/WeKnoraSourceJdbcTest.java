package cn.wayuwa.wao.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class WeKnoraSourceJdbcTest {
    @Test void targetedStatusUsesSameContentHashAsExportAndPreservesPrivateBoundary() {
        var db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:weknora-source;MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        db.execute("CREATE TABLE kb(id VARCHAR(64),perm VARCHAR(32),deleted_at TIMESTAMP)");
        db.execute("CREATE TABLE doc(id VARCHAR(64),title VARCHAR(200),content_markdown TEXT,blocks_json TEXT,tags_json TEXT,kb_id VARCHAR(64),author_id VARCHAR(64),deleted_at TIMESTAMP)");
        db.update("INSERT INTO kb(id,perm) VALUES('public','所有人'),('private','仅自己')");
        db.update("INSERT INTO doc(id,title,content_markdown,kb_id,author_id) VALUES('shared','共享资料','可见正文','public','other'),('own','我的记录','我的正文','private','owner'),('hidden','他人私有','不可泄漏','private','other')");
        var source=new WeKnoraSourceService(db,new ObjectMapper());
        var targeted=source.documents("owner",List.of("shared","own","hidden","missing"));
        assertEquals(2,targeted.size());
        assertEquals(source.snapshot("owner",List.of("docs")),targeted);
        assertTrue(targeted.stream().allMatch(s->s.originalUrl().startsWith("/kb/")));
        assertTrue(targeted.stream().noneMatch(s->s.content().contains("不可泄漏") || s.content().contains("source_kb_id")));
        assertEquals("/kb/private?doc=own",targeted.get(0).originalUrl());
        assertThrows(IllegalArgumentException.class,()->source.documents("owner",List.of()));
    }
}
