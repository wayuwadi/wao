package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.wayuwa.wao.common.*;
import cn.wayuwa.wao.dto.DigitalMemberDtos.*;
import cn.wayuwa.wao.mapper.AiAssistantRunMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockMultipartFile;

class DigitalMemberServiceTest {
    DigitalMemberService service; JdbcTemplate db; AiAssistantRunMapper runMapper;
    DriverManagerDataSource source;
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        source=ds;
        db=new JdbcTemplate(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V189__digital_member_library.sql")).execute(ds);
        db.execute("CREATE TABLE ai_assistant_run(id VARCHAR(32) PRIMARY KEY,status VARCHAR(32))");
        var local=mock(LocalNoteAiService.class);when(local.ready()).thenReturn(true);
        when(local.capabilityId()).thenReturn("local-test");
        @SuppressWarnings("unchecked") ObjectProvider<LocalNoteAiService> provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(local);
        runMapper=mock(AiAssistantRunMapper.class);
        when(runMapper.insertReadOnlyQueued(any())).thenAnswer(call->{cn.wayuwa.wao.domain.AiAssistantRunEntity r=call.getArgument(0);db.update("INSERT INTO ai_assistant_run(id,status) VALUES(?,?)",r.getId(),r.getStatus());when(runMapper.selectById(r.getId())).thenReturn(r);return 1;});
        service=new DigitalMemberService(db,new ObjectMapper(),provider,runMapper,new MockEnvironment().withProperty("wao.ai.local-notes.reference-images-enabled","true"));
    }
    private Subject person(String name) {return service.create("a",new Create(name,"human","家人","male","长发，圆框眼镜"));}
    @Test void deletingDefaultVersionKeepsHistoryAndFrozenReferencesAndRestoreDoesNotRebind() throws Exception {
        var s=person("本人");var p=photo(s.id());var look=service.createLook("a",s.id(),new CreateLook("本人形象","",List.of(p.id()),p.id()));
        String version=look.versions().get(0).id();service.confirm("a",version);service.bind("a",new Bind(s.id(),version,0L));
        var frozen=service.freeze("a",new IdentitySelection("explicit",s.id(),version));service.record("a","retained-run","ARTICLE_IMAGE","hash",frozen);
        var removed=service.deleteVersion("a",version,new VersionLifecycle(look.revision()));
        assertThat(removed.looks().get(0).versions().get(0).state()).isEqualTo("deleted");
        assertThat(service.binding("a").subjectId()).isEqualTo(s.id());assertThat(service.binding("a").versionId()).isNull();
        assertThat(service.references("a","retained-run")).hasSize(1);assertThat(removed.history()).hasSize(1);
        assertThatThrownBy(()->service.freeze("a",new IdentitySelection("explicit",s.id(),version))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->service.confirm("a",version)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->service.restoreVersion("a",version,new VersionLifecycle(look.revision()))).isInstanceOf(ConflictException.class);
        var restored=service.restoreVersion("a",version,new VersionLifecycle(removed.looks().get(0).revision()));
        assertThat(restored.looks().get(0).versions().get(0).state()).isEqualTo("confirmed");assertThat(service.binding("a").versionId()).isNull();
        assertThatThrownBy(()->service.deleteVersion("b",version,new VersionLifecycle(0L))).isInstanceOf(NotFoundException.class);
    }
    @Test void candidateRestoresAsCandidateAndDeletedNumbersAreNeverReused() throws Exception {
        var s=person("候选");var p=photo(s.id());var look=service.createLook("a",s.id(),new CreateLook("候选","",List.of(p.id()),null));
        String version=look.versions().get(0).id();var deleted=service.deleteVersion("a",version,new VersionLifecycle(look.revision()));
        assertThat(service.deleteVersion("a",version,new VersionLifecycle(look.revision())).looks().get(0).revision()).isEqualTo(deleted.looks().get(0).revision());
        var next=service.createVersion("a",look.id(),new CreateVersion(List.of(p.id()),null,deleted.looks().get(0).revision()));
        assertThat(next.versions().stream().map(Version::number)).containsExactly(2,1);
        var restored=service.restoreVersion("a",version,new VersionLifecycle(next.revision()));
        assertThat(restored.looks().get(0).versions().stream().filter(v->v.id().equals(version)).findFirst().orElseThrow().state()).isEqualTo("candidate");
    }
    private Asset photo(String subject) throws Exception {
        var b=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(50,60,BufferedImage.TYPE_INT_RGB),"png",b);
        return service.upload("a",subject,new MockMultipartFile("file","real.png","text/html",b.toByteArray()),"reference");
    }
    @Test void identityFactsDoNotInferFromNameOrPhotoAndOwnersRemainPrivate() throws Exception {
        var s=service.create("a",new Create("女孩名字","human","",null,""));
        assertThat(s.gender()).isEqualTo("unknown");
        var p=photo(s.id());assertThat(service.subject("a",s.id()).gender()).isEqualTo("unknown");
        assertThat(service.raw("a",p.id()).contentType()).isEqualTo("image/png");
        assertThat(service.library("b").subjects()).isEmpty();
        assertThatThrownBy(()->service.raw("b",p.id())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(()->service.subject("b",s.id())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(()->service.library(null)).isInstanceOf(UnauthorizedException.class);
    }
    @Test void versionsAreAppendedAndConfirmationAndBindingRequireCorrectSubject() throws Exception {
        var self=person("本人");var brother=person("双胞胎弟弟");var a=photo(self.id());var b=photo(brother.id());
        var look=service.createLook("a",self.id(),new CreateLook("日常","插画",List.of(a.id()),null));var v=look.versions().get(0);
        assertThatThrownBy(()->service.bind("a",new Bind(self.id(),v.id(),0L))).hasMessageContaining("先确认");
        service.confirm("a",v.id());service.bind("a",new Bind(self.id(),v.id(),0L));
        assertThat(service.profile("a").gender()).isEqualTo("male");
        assertThatThrownBy(()->service.bind("a",new Bind(brother.id(),v.id(),1L))).hasMessageContaining("不属于");
        assertThatThrownBy(()->service.createLook("a",self.id(),new CreateLook("错图","",List.of(b.id()),null))).isInstanceOf(NotFoundException.class);
        var next=service.createVersion("a",look.id(),new CreateVersion(List.of(a.id()),null,0L));
        assertThat(next.versions()).hasSize(2);assertThat(next.versions().get(1).state()).isEqualTo("confirmed");
        assertThatThrownBy(()->service.createVersion("a",look.id(),new CreateVersion(List.of(a.id()),null,0L))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->service.deleteAsset("a",a.id())).isInstanceOf(ConflictException.class);
    }
    @Test void frozenManifestAndDefaultAreStableAcrossLaterProfileAndBindingChanges() throws Exception {
        var s=person("本人");var a=photo(s.id());var l=service.createLook("a",s.id(),new CreateLook("插画","暖色",List.of(a.id()),null));var v=l.versions().get(0);
        service.confirm("a",v.id());service.bind("a",new Bind(s.id(),v.id(),0L));
        var frozen=service.freeze("a",null);String hash=service.inputHash("正文","偏好","model",null);
        service.record("a","run","ARTICLE_IMAGE",hash,frozen);
        service.updateProfile("a",new ProfileUpdate("female",service.profile("a").revision()));
        service.bind("a",new Bind(null,null,1L));
        assertThat(frozen.json()).contains("\"gender\":\"male\"");
        assertThat(service.references("a","run")).hasSize(1);
        service.verifyRetry("a","run",hash);
        assertThatThrownBy(()->service.verifyRetry("a","run",service.inputHash("新正文","偏好","model",null))).isInstanceOf(ConflictException.class);
        db.update("UPDATE digital_asset SET content=? WHERE id=?",new byte[]{1},a.id());
        assertThatThrownBy(()->service.references("a","run")).hasMessageContaining("变化");
    }
    @Test void noneModeSuppressesIdentitiesAndPetCannotBecomeSelf() {
        var pet=service.create("a",new Create("狗狗","pet","家人","unknown","黑白毛色"));
        assertThatThrownBy(()->service.bind("a",new Bind(pet.id(),null,0L))).hasMessageContaining("人物");
        var none=service.freeze("a",new IdentitySelection("none",null,null));
        assertThat(none.subjectId()).isNull();assertThat(none.prompt()).contains("不出现具体人物");
        assertThatThrownBy(()->service.freeze("a",new IdentitySelection("explicit",null,null))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->service.update("a",pet.id(),new Update("改名",null,null,null,null,null,99L))).isInstanceOf(ConflictException.class);
        var selection=new IdentitySelection("none",null,null);service.rememberNoteSelection("a","doc","cycle",selection);
        assertThat(service.noteSelection("a","doc","cycle")).isEqualTo(selection);
        assertThat(service.noteSelection("b","doc","cycle")).isNull();
        assertThat(service.noteSelection("a","doc","new-cycle")).isNull();
    }
    @Test void thinReferenceKeepsAtLeastOnePixelWhenNormalized() throws Exception {
        var self=person("本人");var bytes=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4096,1,BufferedImage.TYPE_INT_RGB),"png",bytes);
        var asset=service.upload("a",self.id(),new MockMultipartFile("file","thin.png","image/png",bytes.toByteArray()),"reference");
        assertThat(asset.width()).isEqualTo(2048);assertThat(asset.height()).isEqualTo(1);
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(service.raw("a",asset.id()).bytes())).getHeight()).isEqualTo(1);
    }
    @Test void rejectsFakeAndHugeImageBeforeAllocatingAndKeepsArchiveHistory() throws Exception {
        var self=person("本人");
        assertThatThrownBy(()->service.upload("a",self.id(),new MockMultipartFile("file","fake.png","image/png",new byte[]{1,2,3}),"reference")).isInstanceOf(BadRequestException.class);
        var a=photo(self.id());service.bind("a",new Bind(self.id(),null,0L));
        var updated=service.subject("a",self.id());
        service.update("a",self.id(),new Update(null,null,null,null,"archived",null,updated.revision()));
        assertThat(service.binding("a").subjectId()).isNull();assertThat(service.raw("a",a.id()).bytes()).isNotEmpty();
        assertThatThrownBy(()->service.createLook("a",self.id(),new CreateLook("新","",List.of(a.id()),null))).hasMessageContaining("归档");
    }
    @Test void generatedCandidateUsesFrozenFactsAndDuplicateCompletionCannotAppendAnotherVersion() throws Exception {
        var s=person("原名");var a=photo(s.id());String request="c".repeat(32);
        service.generate("a",s.id(),new Generate("候选","插画",List.of(a.id()),request));
        var captured=org.mockito.ArgumentCaptor.forClass(cn.wayuwa.wao.domain.AiAssistantRunEntity.class);verify(runMapper).insertReadOnlyQueued(captured.capture());var run=captured.getValue();
        service.update("a",s.id(),new Update("新名",null,"female",null,null,null,0L));
        when(runMapper.completeProviderSucceeded(request,"claim","私有候选形象已保存")).thenReturn(1,0);
        byte[] png=service.raw("a",a.id()).bytes();
        assertThat(service.completeImage(run,"claim",png)).isEqualTo(1);
        assertThat(service.completeImage(run,"claim",png)).isZero();
        var saved=service.subject("a",s.id());assertThat(saved.looks()).hasSize(1);assertThat(saved.looks().get(0).versions().get(0).state()).isEqualTo("candidate");
        assertThat(db.queryForObject("SELECT snapshot_json FROM digital_appearance_version WHERE look_id=?",String.class,saved.looks().get(0).id())).contains("原名","male").doesNotContain("新名");
    }
    @Test void resultAndPrivateAssetRollbackTogetherWhenArchivedDuringGeneration() throws Exception {
        var s=person("本人");var a=photo(s.id());String request="b".repeat(32);
        service.generate("a",s.id(),new Generate("候选","插画",List.of(a.id()),request));
        var captured=org.mockito.ArgumentCaptor.forClass(cn.wayuwa.wao.domain.AiAssistantRunEntity.class);verify(runMapper).insertReadOnlyQueued(captured.capture());var run=captured.getValue();
        service.update("a",s.id(),new Update(null,null,null,null,"archived",null,0L));
        when(runMapper.completeProviderSucceeded(request,"claim","私有候选形象已保存")).thenAnswer(call->{db.update("UPDATE ai_assistant_run SET status='succeeded' WHERE id=?",request);return 1;});
        var transaction=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
        byte[] png=service.raw("a",a.id()).bytes();
        assertThatThrownBy(()->transaction.execute(status->service.completeImage(run,"claim",png))).hasMessageContaining("归档");
        assertThat(db.queryForObject("SELECT status FROM ai_assistant_run WHERE id=?",String.class,request)).isEqualTo("secure_queued");
        assertThat(service.subject("a",s.id()).looks()).isEmpty();assertThat(service.subject("a",s.id()).assets()).hasSize(1);
    }
}
