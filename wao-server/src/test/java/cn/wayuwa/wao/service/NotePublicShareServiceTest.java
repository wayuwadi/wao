package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.*;
import cn.wayuwa.wao.dto.SharedDocDto;
import cn.wayuwa.wao.domain.ImageBlobEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotePublicShareServiceTest {
    private JdbcTemplate db;
    private ShareService shares;
    private ImageService images;
    private NotePublicShareService service;
    private final Instant now=Instant.parse("2026-10-12T01:00:00Z");
    @BeforeEach void setup() {
        db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        db.execute("CREATE TABLE doc(id VARCHAR(32) PRIMARY KEY,author_id VARCHAR(32),kb_id VARCHAR(32),deleted_at TIMESTAMP)");
        db.execute("CREATE TABLE note_public_share(id CHAR(32) PRIMARY KEY,doc_id VARCHAR(32),owner_id VARCHAR(32),token_hash CHAR(64) UNIQUE,snapshot_json LONGTEXT,created_at TIMESTAMP,expires_at TIMESTAMP,revoked_at TIMESTAMP)");
        db.update("INSERT INTO doc(id,author_id,kb_id) VALUES ('note','owner','kb-notes')");
        shares=mock(ShareService.class);images=mock(ImageService.class);
        when(shares.documentSnapshot("note","owner")).thenReturn(new SharedDocDto("note","标题",true,"kb-notes","保存时间","- [ ] 原始清单\n  描述\n![图](/api/images/pic/raw)"));
        service=new NotePublicShareService(db,shares,images,new ObjectMapper(),Clock.fixed(now,ZoneOffset.UTC));
    }
    @Test void noImplicitSharingAndOnlyAuthorMayManage() {
        assertThat(service.list("owner","note")).isEmpty();
        assertThatThrownBy(()->service.create(null,"note",168)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(()->service.create("other","note",168)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(()->service.list("other","note")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(()->service.create("owner","note",0)).isInstanceOf(BadRequestException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM note_public_share",Integer.class)).isZero();
    }
    @Test void randomTokenIsHashedAndSnapshotNeverFollowsFutureEdits() {
        var created=service.create("owner","note",168);
        assertThat(created.token()).matches("ns_[A-Za-z0-9_-]{43}");
        assertThat(created.expiresAt()).isEqualTo(now.plus(Duration.ofDays(7)));
        assertThat(db.queryForObject("SELECT token_hash FROM note_public_share",String.class)).hasSize(64).doesNotContain(created.token());
        when(shares.documentSnapshot("note","owner")).thenReturn(new SharedDocDto("note","私有新增",true,"kb-notes","","不能公开的未来正文"));
        var read=service.read(created.token());
        assertThat(read.doc().md()).contains("- [ ] 原始清单\n  描述","/api/share/notes/"+created.token()+"/images/pic/raw").doesNotContain("未来正文");
        assertThat(service.list("owner","note")).hasSize(1);
        var second=service.create("owner","note",1);
        assertThat(second.token()).isNotEqualTo(created.token());
        service.revoke("owner","note",second.id());
        assertThat(service.read(created.token()).doc().title()).isEqualTo("标题");
        assertThatThrownBy(()->service.read(second.token())).isInstanceOf(NotFoundException.class);
    }
    @Test void sameCapabilityProtectsImagesAndRejectsCrossSnapshotIds() {
        var created=service.create("owner","note",24);
        ImageBlobEntity image=new ImageBlobEntity();when(images.raw("pic")).thenReturn(image);
        assertThat(service.image(created.token(),"pic")).isSameAs(image);
        assertThatThrownBy(()->service.image(created.token(),"other-private")).isInstanceOf(NotFoundException.class);
        verify(images,never()).raw("other-private");
        assertThatThrownBy(()->service.revoke("other","note",created.id())).isInstanceOf(ForbiddenException.class);
        service.revoke("owner","note",created.id());service.revoke("owner","note",created.id());
        assertThatThrownBy(()->service.read(created.token())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(()->service.image(created.token(),"pic")).isInstanceOf(NotFoundException.class);
    }
    @Test void invalidExpiredDeletedAndTransferredSharesFailClosed() {
        assertThatThrownBy(()->service.read("note")).isInstanceOf(NotFoundException.class);
        var created=service.create("owner","note",1);
        var expired=new NotePublicShareService(db,shares,images,new ObjectMapper(),Clock.fixed(now.plusSeconds(3600),ZoneOffset.UTC));
        assertThatThrownBy(()->expired.read(created.token())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(()->expired.image(created.token(),"pic")).isInstanceOf(NotFoundException.class);
        db.update("UPDATE doc SET deleted_at=CURRENT_TIMESTAMP WHERE id='note'");
        assertThatThrownBy(()->service.read(created.token())).isInstanceOf(NotFoundException.class);
        db.update("UPDATE doc SET deleted_at=NULL,author_id='other' WHERE id='note'");
        assertThatThrownBy(()->service.read(created.token())).isInstanceOf(NotFoundException.class);
    }
    @Test void ownAbsoluteImagesAreMappedButForeignUrlsDoNotGrantLocalImageAccess() {
        when(shares.documentSnapshot("note","owner")).thenReturn(new SharedDocDto("note","标题",true,"kb-notes","",
                "![本站](https://wao.wayuwa.cn/api/images/own/raw)\n![外站](https://else.example/api/images/foreign/raw)"));
        var created=service.create("owner","note",1);
        assertThat(service.read(created.token()).doc().md()).contains("/api/share/notes/"+created.token()+"/images/own/raw","https://else.example/api/images/foreign/raw");
        assertThatThrownBy(()->service.image(created.token(),"foreign")).isInstanceOf(NotFoundException.class);
        verify(images,never()).raw("foreign");
        service.image(created.token(),"own");verify(images).raw("own");
    }

    @Test void knowledgeDocumentsUseTheSameRevocableSnapshotAndOwnerBoundary() {
        db.update("INSERT INTO doc(id,author_id,kb_id) VALUES ('document','owner','knowledge')");
        when(shares.documentSnapshot("document","owner")).thenReturn(
                new SharedDocDto("document","知识库文档",false,"knowledge","保存时间","原始正文 ![图](/api/images/doc-image/raw)"));
        assertThatThrownBy(()->service.create("other","document",24)).isInstanceOf(ForbiddenException.class);
        var created=service.create("owner","document",24);
        assertThat(service.read(created.token()).doc().note()).isFalse();
        assertThat(service.read(created.token()).doc().md()).contains("/api/share/notes/"+created.token()+"/images/doc-image/raw");
        when(shares.documentSnapshot("document","owner")).thenReturn(
                new SharedDocDto("document","未来标题",false,"knowledge","","不能自动公开的编辑"));
        assertThat(service.read(created.token()).doc().title()).isEqualTo("知识库文档");
        var expired=new NotePublicShareService(db,shares,images,new ObjectMapper(),Clock.fixed(now.plus(Duration.ofDays(1)),ZoneOffset.UTC));
        assertThatThrownBy(()->expired.read(created.token())).isInstanceOf(NotFoundException.class);
        db.update("UPDATE doc SET author_id='other' WHERE id='document'");
        assertThatThrownBy(()->service.read(created.token())).isInstanceOf(NotFoundException.class);
        db.update("UPDATE doc SET author_id='owner' WHERE id='document'");
        service.revoke("owner","document",created.id());
        assertThatThrownBy(()->service.read(created.token())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(()->service.image(created.token(),"doc-image")).isInstanceOf(NotFoundException.class);
    }
}
