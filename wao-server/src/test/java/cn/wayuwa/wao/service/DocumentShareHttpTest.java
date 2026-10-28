package cn.wayuwa.wao.service;

import cn.wayuwa.wao.auth.*;
import cn.wayuwa.wao.common.GlobalExceptionHandler;
import cn.wayuwa.wao.controller.*;
import cn.wayuwa.wao.domain.ImageBlobEntity;
import cn.wayuwa.wao.dto.SharedDocDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 使用真实分享存储与 HTTP 序列化检查匿名读取、撤销和旧出口封闭。 */
class DocumentShareHttpTest {
    private MockMvc mvc;
    private JdbcTemplate db;
    private ObjectMapper json;

    @BeforeEach void setup() {
        db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        db.execute("CREATE TABLE doc(id VARCHAR(32) PRIMARY KEY,author_id VARCHAR(32),kb_id VARCHAR(32),deleted_at TIMESTAMP)");
        db.execute("CREATE TABLE note_public_share(id CHAR(32) PRIMARY KEY,doc_id VARCHAR(32),owner_id VARCHAR(32),token_hash CHAR(64) UNIQUE,snapshot_json LONGTEXT,created_at TIMESTAMP,expires_at TIMESTAMP,revoked_at TIMESTAMP)");
        db.update("INSERT INTO doc(id,author_id,kb_id) VALUES ('document','owner','knowledge')");
        var shares=spy(new ShareService(null,null,null,null));
        doReturn(new SharedDocDto("document","测试文档",false,"knowledge","","已保存正文 ![图](/api/images/picture/raw)"))
                .when(shares).documentSnapshot("document","owner");
        var images=mock(ImageService.class);
        var image=new ImageBlobEntity(); image.setContentType("image/png"); image.setContent(new byte[]{1,2,3});
        when(images.raw("picture")).thenReturn(image);
        json=new ObjectMapper().findAndRegisterModules();
        var service=new NotePublicShareService(db,shares,images,json);
        var sessions=mock(AuthSessionManager.class);
        when(sessions.currentAccountId(any())).thenAnswer(call -> {
            jakarta.servlet.http.HttpServletRequest request=call.getArgument(0);
            return request.getHeader("X-Test-Session");
        });
        var member=mock(CurrentMember.class);
        when(member.id(any())).thenAnswer(call -> {
            jakarta.servlet.http.HttpServletRequest request=call.getArgument(0);
            return "test-account".equals(request.getHeader("X-Test-Session")) ? "owner" : null;
        });
        mvc=MockMvcBuilders.standaloneSetup(new ShareController(shares,"https://test.example"),
                new NotePublicShareController(service,member,shares,"https://test.example"))
                .setControllerAdvice(new GlobalExceptionHandler(),new NotePrivacyResponseAdvice())
                .addInterceptors(new AuthInterceptor(new AuthProperties(true,false),sessions))
                .build();
    }

    @Test void tokenReadsJsonMarkdownAndImagesUntilRevokedAndNeverUnlocksOldIds() throws Exception {
        mvc.perform(post("/api/docs/document/public-shares").contentType(MediaType.APPLICATION_JSON).content("{\"durationHours\":24}"))
                .andExpect(status().isUnauthorized());
        String result=mvc.perform(post("/api/docs/document/public-shares").header("X-Test-Session","test-account")
                .contentType(MediaType.APPLICATION_JSON).content("{\"durationHours\":24}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"))
                .andReturn().getResponse().getContentAsString();
        var created=json.readTree(result).path("data");
        String token=created.path("token").asText(),id=created.path("id").asText();
        String path="/api/share/notes/"+token;
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.data.doc.note").value(false))
                .andExpect(jsonPath("$.data.doc.title").value("测试文档"))
                .andExpect(header().string("X-Robots-Tag","noindex, nofollow, noarchive"))
                .andExpect(header().string("Referrer-Policy","no-referrer"));
        mvc.perform(get(path+".md")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("https://test.example"+path+"/images/picture/raw")))
                .andExpect(header().string("Cache-Control","private, no-store"));
        mvc.perform(get(path+"/images/picture/raw")).andExpect(status().isOk()).andExpect(content().bytes(new byte[]{1,2,3}));
        mvc.perform(get(path+"/images/another/raw")).andExpect(status().isNotFound());
        for(String suffix:new String[]{"",".md"}) {
            mvc.perform(get("/api/share/docs/document"+suffix).param("shareToken",token))
                    .andExpect(status().isNotFound()).andExpect(header().string("Cache-Control","private, no-store"));
        }
        mvc.perform(delete("/api/docs/document/public-shares/"+id).header("X-Test-Session","test-account"))
                .andExpect(status().isOk());
        for(String suffix:new String[]{"",".md","/images/picture/raw"}) {
            mvc.perform(get(path+suffix)).andExpect(status().isNotFound())
                    .andExpect(header().string("Cache-Control","private, no-store"))
                    .andExpect(header().string("X-Robots-Tag","noindex, nofollow, noarchive"));
        }
    }
}
