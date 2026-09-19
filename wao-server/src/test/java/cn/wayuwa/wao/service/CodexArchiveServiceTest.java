package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.wayuwa.wao.common.*;
import cn.wayuwa.wao.dto.CodexArchiveBatchRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class CodexArchiveServiceTest {
    MemoryAccessTokenService tokens = mock(MemoryAccessTokenService.class);
    JdbcTemplate jdbc;
    CodexArchiveService service;

    @BeforeEach void setup() {
        var source = new JdbcDataSource(); source.setURL("jdbc:h2:mem:archive-" + java.util.UUID.randomUUID() + ";MODE=MySQL");
        jdbc = new JdbcTemplate(source);
        // Keep one connection via DB_CLOSE_DELAY only for this isolated in-memory database.
        source.setURL(source.getURL() + ";DB_CLOSE_DELAY=-1");
        jdbc.execute("CREATE TABLE kb(id VARCHAR PRIMARY KEY,name VARCHAR,icon VARCHAR,color VARCHAR,auto INT,notes INT,description VARCHAR,perm VARCHAR,ord INT,deleted_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE kb_folder(id VARCHAR PRIMARY KEY,kb_id VARCHAR,name VARCHAR,ord INT,cascade_del INT,deleted_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE doc(id VARCHAR PRIMARY KEY,kb_id VARCHAR,folder_id VARCHAR,title VARCHAR,author_id VARCHAR,auto INT,blocks_json VARCHAR,content_markdown VARCHAR,links_json VARCHAR,tags_json VARCHAR,ord INT,created_at TIMESTAMP,updated_at TIMESTAMP,rev INT,cascade_del INT,deleted_at TIMESTAMP)");
        service = new CodexArchiveService(tokens, jdbc, new ObjectMapper());
    }

    CodexArchiveBatchRequest batch(int part) {
        return new CodexArchiveBatchRequest("device-1", "Mac", "session-1", "/project", "rollout.jsonl", 0, 200, part, "Codex 对话", "用户问题与工作过程");
    }

    @Test void authenticatesOnlyExplicitArchiveGrant() {
        assertThatThrownBy(() -> service.authenticate(null)).isInstanceOf(UnauthorizedException.class);
        var token = new MemoryAccessTokenService.AuthenticatedToken("t1", "u1");
        when(tokens.authenticateToken("device-secret")).thenReturn(token);
        assertThatThrownBy(() -> service.authenticate("Bearer device-secret")).isInstanceOf(ForbiddenException.class);
        when(tokens.permits(token, "codex_archive", "u1", "write")).thenReturn(true);
        assertThat(service.authenticate("Bearer device-secret")).isEqualTo("u1");
    }

    @Test void duplicateNeverOverwritesEditedOrDeletedDocumentAndMembersCannotCollide() {
        String id = (String) service.append("u1", batch(0)).get("docId");
        jdbc.update("UPDATE doc SET content_markdown='人工编辑', deleted_at=NOW() WHERE id=?", id);
        var duplicate = service.append("u1", batch(0));
        assertThat(duplicate.get("duplicate")).isEqualTo(true);
        assertThat(duplicate.get("deleted")).isEqualTo(true);
        assertThat(jdbc.queryForObject("SELECT content_markdown FROM doc WHERE id=?", String.class, id)).isEqualTo("人工编辑");
        assertThat(service.append("u2", batch(0)).get("docId")).isNotEqualTo(id);
        assertThat(service.append("u1", batch(1)).get("docId")).isNotEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM doc", Integer.class)).isEqualTo(3);
    }

    @Test void deletedLibraryIsNotResurrected() {
        service.append("u1", batch(0));
        jdbc.update("UPDATE kb SET deleted_at=NOW()");
        assertThatThrownBy(() -> service.append("u1", batch(1))).isInstanceOf(ConflictException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM doc", Integer.class)).isEqualTo(1);
    }
}
