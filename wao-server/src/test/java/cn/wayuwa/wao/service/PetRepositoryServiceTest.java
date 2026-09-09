package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.*;
import cn.wayuwa.wao.domain.SkillPublisherEntity;
import cn.wayuwa.wao.mapper.SkillPublisherMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PetRepositoryServiceTest {
    JdbcTemplate jdbc;
    PetRepositoryService service;
    @BeforeEach void setUp() throws Exception {
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:pets"+System.nanoTime()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("CREATE TABLE skill_publisher(id VARCHAR(64) PRIMARY KEY, handle VARCHAR(32),display_name VARCHAR(64))");
        String migration=Files.readString(Path.of("src/main/resources/db/migration/V149__pet_repository.sql"));
        jdbc.execute(migration);
        var publishers=mock(SkillPublisherMapper.class);
        for(String name:java.util.List.of("author","other")) {
            var publisher=new SkillPublisherEntity();publisher.setId(name);publisher.setMemberId(name);publisher.setHandle(name);publisher.setDisplayName(name);
            when(publishers.findByMember(name)).thenReturn(publisher);jdbc.update("INSERT INTO skill_publisher VALUES(?,?,?)",name,name,name);
        }
        service=new PetRepositoryService(jdbc,publishers,new PetPackageInspector(new ObjectMapper()),mock(SkillRateLimiter.class));
    }
    @Test void completesPrivatePublishPublicDownloadAndUnlistWithOwnershipProtection() throws Exception {
        byte[] sprite=PetPackageInspectorTest.png(2288);
        var pet=service.create("author",null,PetPackageInspectorTest.manifest,sprite,"原创验收图集","CC0-1.0");
        assertThat(pet.listed()).isFalse();assertThat(service.browse("",1).items()).isEmpty();
        assertThat(service.mine("author")).hasSize(1);assertThat(service.mine("other")).isEmpty();
        assertThatThrownBy(()->service.detail(pet.id())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(()->service.binary(pet.id(),null,true,"client")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(()->service.binary(pet.id(),"other",false,"client")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(()->service.visibility("other",pet.id(),true,true)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(()->service.visibility("author",pet.id(),true,false)).isInstanceOf(BadRequestException.class);
        var same=service.create("author",null,PetPackageInspectorTest.manifest,sprite,"原创验收图集","CC0-1.0");
        assertThat(same.id()).isEqualTo(pet.id());
        service.visibility("author",pet.id(),true,true);
        assertThat(service.browse("测试",1).items()).hasSize(1);assertThat(service.browse("%",1).items()).isEmpty();
        var publicDownload=service.binary(pet.id(),null,false,"client");
        assertThat(publicDownload.sha256()).isEqualTo(pet.sha256());
        assertThat(publicDownload.content()).isEqualTo(service.binary(pet.id(),"author",false,"client").content());
        service.visibility("author",pet.id(),false,false);
        assertThatThrownBy(()->service.binary(pet.id(),null,false,"client")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(()->service.binary(pet.id(),null,true,"client")).isInstanceOf(NotFoundException.class);
        assertThat(service.binary(pet.id(),"author",false,"client").sha256()).isEqualTo(pet.sha256());
    }
    @Test void requiresRealMemberAndDeclaredLicense() throws Exception {
        assertThatThrownBy(()->service.mine(null)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(()->service.create("missing",null,"{}",new byte[0],"原创","MIT")).hasMessageContaining("作者身份");
        assertThatThrownBy(()->service.create("author",null,"{}",new byte[0],"原创","UNKNOWN")).hasMessageContaining("授权");
    }
}
