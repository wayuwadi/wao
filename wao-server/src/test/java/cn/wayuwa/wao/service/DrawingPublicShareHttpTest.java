package cn.wayuwa.wao.service;

import cn.wayuwa.wao.auth.AuthInterceptor;
import cn.wayuwa.wao.auth.AuthProperties;
import cn.wayuwa.wao.auth.AuthSessionManager;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.GlobalExceptionHandler;
import cn.wayuwa.wao.controller.DrawingPublicShareController;
import cn.wayuwa.wao.controller.NotePrivacyResponseAdvice;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:drawing_share_http;MODE=MySQL;DB_CLOSE_DELAY=-1")
class DrawingPublicShareHttpTest {
    private static final String MANAGE = "/api/drawings/personal/public-shares";
    private static final String LINKED = "/api/drawings/linked/public-shares";
    private static final String PUBLIC = "/api/share/drawings/current";
    private static final String TOKEN_HEADER = "X-Drawing-Share-Token";
    private static final String SESSION = "X-Test-Session";
    private static final String SCENE = """
            {
              "elements": [
                {"id":"shape","type":"rectangle","link":"https://example.test","customData":{"secret":"private"}},
                {"id":"deleted","type":"rectangle","isDeleted":true},
                {"id":"embed","type":"embeddable","link":"https://example.test"},
                {"id":"picture","type":"image","fileId":"inline"},
                {"id":"remote","type":"image","fileId":"external"}
              ],
              "appState":{"viewBackgroundColor":"#ffffff","selectedElementIds":{"shape":true}},
              "files":{
                "inline":{"dataURL":"data:image/png;base64,AAAA","created":7},
                "external":{"dataURL":"https://example.test/file.png"},
                "unused":{"dataURL":"data:image/png;base64,BBBB"}
              }
            }
            """;

    @Autowired private JdbcTemplate db;
    @Autowired private DrawingPublicShareService service;
    @Autowired private ObjectMapper json;
    private MockMvc mvc;

    @BeforeEach void setup() {
        db.execute("DROP TABLE IF EXISTS drawing_public_share");
        db.execute("DROP TABLE IF EXISTS drawing_file");
        db.execute("DROP TABLE IF EXISTS doc");
        db.execute("DROP TABLE IF EXISTS kb");
        db.execute("""
                CREATE TABLE kb(id VARCHAR(32) PRIMARY KEY, name VARCHAR(255), icon VARCHAR(255), color VARCHAR(40),
                  project_id VARCHAR(32), auto BOOLEAN, description TEXT, group_id VARCHAR(32), perm VARCHAR(40),
                  owner_id VARCHAR(32), ord INT, notes BOOLEAN, deleted_at TIMESTAMP, deleted_by VARCHAR(32), directory_json TEXT)
                """);
        db.execute("CREATE TABLE doc(id VARCHAR(32) PRIMARY KEY, kb_id VARCHAR(32), title VARCHAR(255), deleted_at TIMESTAMP)");
        db.execute("""
                CREATE TABLE drawing_file (
                  id VARCHAR(32) PRIMARY KEY, owner_member_id VARCHAR(32) NOT NULL,
                  knowledge_doc_id VARCHAR(32), title VARCHAR(128) NOT NULL, revision INT NOT NULL,
                  updated_at TIMESTAMP NOT NULL, scene_json LONGTEXT NOT NULL, deleted_at TIMESTAMP
                )
                """);
        db.execute("""
                CREATE TABLE drawing_public_share (
                  id CHAR(32) PRIMARY KEY, drawing_id VARCHAR(32) NOT NULL, owner_member_id VARCHAR(32) NOT NULL,
                  token_hash CHAR(64) UNIQUE NOT NULL, created_at TIMESTAMP NOT NULL, expires_at TIMESTAMP,
                  revoked_at TIMESTAMP, token_ciphertext VARBINARY(96), token_nonce BINARY(12),
                  FOREIGN KEY (drawing_id) REFERENCES drawing_file(id) ON DELETE CASCADE
                )
                """);
        db.update("INSERT INTO drawing_file VALUES ('personal','owner',NULL,'Old title',1,CURRENT_TIMESTAMP,?,NULL)", SCENE);
        db.update("INSERT INTO drawing_file VALUES ('linked','owner','doc','Knowledge',1,CURRENT_TIMESTAMP,?,NULL)", SCENE);
        db.update("INSERT INTO kb(id,owner_id,perm) VALUES ('kb','library-owner','成员可编辑')");
        db.update("INSERT INTO doc(id,kb_id,title) VALUES ('doc','kb','Library title')");
        var sessions = mock(AuthSessionManager.class);
        var member = mock(CurrentMember.class);
        when(sessions.currentAccountId(any())).thenAnswer(call -> ((HttpServletRequest) call.getArgument(0)).getHeader(SESSION));
        when(member.id(any())).thenAnswer(call -> ((HttpServletRequest) call.getArgument(0)).getHeader(SESSION));
        mvc = MockMvcBuilders.standaloneSetup(new DrawingPublicShareController(member, service))
                .setControllerAdvice(new GlobalExceptionHandler(), new NotePrivacyResponseAdvice())
                .addInterceptors(new AuthInterceptor(new AuthProperties(true, true), sessions))
                .build();
    }

    @Test void sameLinkReadsLatestCommittedContentAndSkipsUnchangedScene() throws Exception {
        var created = create("7d");
        var token = created.path("token").asText();
        assertThat(token).matches("ds_[A-Za-z0-9_-]{43}");
        assertThat(db.queryForObject("SELECT token_hash FROM drawing_public_share", String.class))
                .hasSize(64).isNotEqualTo(token);
        assertThat(db.queryForObject("SELECT token_ciphertext FROM drawing_public_share", byte[].class))
                .isNotEqualTo(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        mvc.perform(get(PUBLIC).header(TOKEN_HEADER, token))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(jsonPath("$.data.title").value("Old title"))
                .andExpect(jsonPath("$.data.scene.elements.length()").value(2))
                .andExpect(jsonPath("$.data.scene.elements[0].link").doesNotExist())
                .andExpect(jsonPath("$.data.scene.elements[0].customData").doesNotExist())
                .andExpect(jsonPath("$.data.scene.files.inline.dataURL").value("data:image/png;base64,AAAA"))
                .andExpect(jsonPath("$.data.scene.files.unused").doesNotExist());
        db.update("UPDATE drawing_file SET title='Renamed' WHERE id='personal'");
        mvc.perform(get(PUBLIC).header(TOKEN_HEADER, token).param("knownRevision", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Renamed"))
                .andExpect(jsonPath("$.data.scene").doesNotExist());
        db.update("UPDATE drawing_file SET revision=2,scene_json=? WHERE id='personal'", SCENE.replace("shape", "new"));
        mvc.perform(get(PUBLIC).header(TOKEN_HEADER, token).param("knownRevision", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(2))
                .andExpect(jsonPath("$.data.scene.elements[0].id").value("new"));
    }

    @Test void managementIsOwnerOnlyAndExpiryRevocationAndDeletionInvalidateLinks() throws Exception {
        mvc.perform(get(MANAGE)).andExpect(status().isUnauthorized());
        mvc.perform(post(MANAGE).header(SESSION, "other").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(get(PUBLIC).header(TOKEN_HEADER, "personal")).andExpect(status().isNotFound());

        var limited = create("1h");
        var permanent = create("permanent");
        var limitedToken = limited.path("token").asText();
        var permanentToken = permanent.path("token").asText();
        assertThat(permanent.path("expiresAt").isNull()).isTrue();
        var limitedId = limited.path("id").asText();
        var permanentId = permanent.path("id").asText();
        mvc.perform(delete(MANAGE + "/" + permanentId).header(SESSION, "other")).andExpect(status().isNotFound());
        db.update("UPDATE drawing_public_share SET expires_at = TIMESTAMP '2000-01-01 00:00:00' WHERE id=?", limitedId);
        mvc.perform(get(PUBLIC).header(TOKEN_HEADER, limitedToken).param("knownRevision", "1"))
                .andExpect(status().isNotFound()).andExpect(header().string("Cache-Control", "private, no-store"));
        mvc.perform(get(MANAGE).header(SESSION, "owner")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].token").value(permanentToken))
                .andExpect(header().string("Cache-Control", "private, no-store"));
        mvc.perform(get(MANAGE).header(SESSION, "other")).andExpect(status().isNotFound());
        mvc.perform(delete(MANAGE + "/" + permanentId).header(SESSION, "owner")).andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT token_ciphertext FROM drawing_public_share WHERE id=?", byte[].class,
                permanentId)).isNull();
        mvc.perform(get(PUBLIC).header(TOKEN_HEADER, permanentToken)).andExpect(status().isNotFound());

        var afterRevoke = create("permanent");
        db.update("DELETE FROM drawing_file WHERE id='personal'");
        mvc.perform(get(PUBLIC).header(TOKEN_HEADER, afterRevoke.path("token").asText())).andExpect(status().isNotFound());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM drawing_public_share", Integer.class)).isZero();
    }

    @Test void linkedDrawingOwnerCanShareCurrentCanvasWhileKnowledgeAccessRemainsValid() throws Exception {
        for (String other : new String[]{"other", "library-owner"}) {
            mvc.perform(post(LINKED).header(SESSION, other).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isNotFound());
            mvc.perform(get(LINKED).header(SESSION, other)).andExpect(status().isNotFound());
        }

        var link = create(LINKED, "permanent");
        String token = link.path("token").asText();
        mvc.perform(get(LINKED).header(SESSION, "owner")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].token").value(token));
        mvc.perform(get(PUBLIC).header(TOKEN_HEADER, token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Library title"))
                .andExpect(jsonPath("$.data.scene.elements[0].link").doesNotExist());

        db.update("UPDATE doc SET title='Renamed library drawing' WHERE id='doc'");
        db.update("UPDATE drawing_file SET revision=2, scene_json=? WHERE id='linked'", SCENE.replace("shape", "new"));
        mvc.perform(get(PUBLIC).header(TOKEN_HEADER, token).param("knownRevision", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.title").value("Renamed library drawing"))
                .andExpect(jsonPath("$.data.scene.elements[0].id").value("new"));

        db.update("UPDATE kb SET perm='仅自己' WHERE id='kb'");
        mvc.perform(get(PUBLIC).header(TOKEN_HEADER, token)).andExpect(status().isNotFound());
        mvc.perform(get(LINKED).header(SESSION, "owner")).andExpect(status().isNotFound());
        db.update("UPDATE kb SET owner_id='owner', deleted_at=CURRENT_TIMESTAMP WHERE id='kb'");
        mvc.perform(get(PUBLIC).header(TOKEN_HEADER, token)).andExpect(status().isNotFound());
        db.update("UPDATE kb SET deleted_at=NULL WHERE id='kb'");
        db.update("UPDATE doc SET deleted_at=CURRENT_TIMESTAMP WHERE id='doc'");
        mvc.perform(get(PUBLIC).header(TOKEN_HEADER, token)).andExpect(status().isNotFound());
        mvc.perform(get(LINKED).header(SESSION, "owner")).andExpect(status().isNotFound());
        db.update("UPDATE doc SET deleted_at=NULL WHERE id='doc'");
        mvc.perform(delete(LINKED + "/" + link.path("id").asText()).header(SESSION, "owner"))
                .andExpect(status().isOk());
        mvc.perform(get(PUBLIC).header(TOKEN_HEADER, token)).andExpect(status().isNotFound());
    }

    @Test void onlyValidTextDisplayStylesSurviveSharing() throws Exception {
        db.update("UPDATE drawing_file SET scene_json=? WHERE id='personal'", """
                {"elements":[
                  {"type":"text","text":"库存预警","originalText":"库存预警","customData":{"private":"secret","waoTextStyle":{
                    "background":"#243a70","source":"库存预警","runs":[{"start":2,"end":4,"color":"#ffffff","private":"secret"},
                    {"start":4,"end":200,"color":"url(example)"}]}}},
                  {"type":"text","text":"新标题","originalText":"新标题","customData":{"waoTextStyle":{
                    "background":"#fff0a8","source":"private old text","runs":[{"start":0,"end":3,"color":"#ffffff"}]}}}
                ]}
                """);
        mvc.perform(get(PUBLIC).header(TOKEN_HEADER, create("7d").path("token").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scene.elements[0].customData.private").doesNotExist())
                .andExpect(jsonPath("$.data.scene.elements[0].customData.waoTextStyle.background").value("#243a70"))
                .andExpect(jsonPath("$.data.scene.elements[0].customData.waoTextStyle.runs.length()").value(1))
                .andExpect(jsonPath("$.data.scene.elements[0].customData.waoTextStyle.runs[0].private").doesNotExist())
                .andExpect(jsonPath("$.data.scene.elements[1].customData.waoTextStyle.source").doesNotExist())
                .andExpect(jsonPath("$.data.scene.elements[1].customData.waoTextStyle.runs").doesNotExist());
    }

    @Test void digestOnlyLinksStillOpenAndCanBeRevokedWithoutReturningAnAddress() throws Exception {
        var created = create("permanent");
        var id = created.path("id").asText();
        db.update("UPDATE drawing_public_share SET token_ciphertext=NULL, token_nonce=NULL WHERE id=?", id);
        mvc.perform(get(MANAGE).header(SESSION, "owner")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(id))
                .andExpect(jsonPath("$.data[0].token").doesNotExist());
        mvc.perform(get(PUBLIC).header(TOKEN_HEADER, created.path("token").asText())).andExpect(status().isOk());
        mvc.perform(delete(MANAGE + "/" + id).header(SESSION, "owner")).andExpect(status().isOk());
    }

    private JsonNode create(String validity) throws Exception {
        return create(MANAGE, validity);
    }

    private JsonNode create(String path, String validity) throws Exception {
        String body = mvc.perform(post(path).header(SESSION, "owner")
                .contentType(MediaType.APPLICATION_JSON).content(json.createObjectNode().put("validity", validity).toString()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data");
    }
}
