package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.ProjectEntity;
import cn.wayuwa.wao.domain.ProjectSkillBindingEntity;
import cn.wayuwa.wao.domain.SkillEntity;
import cn.wayuwa.wao.domain.SkillVersionEntity;
import cn.wayuwa.wao.dto.AiSkillAuditDto;
import cn.wayuwa.wao.mapper.ProjectMapper;
import cn.wayuwa.wao.mapper.ProjectSkillBindingMapper;
import cn.wayuwa.wao.mapper.SkillMapper;
import cn.wayuwa.wao.mapper.SkillVersionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AiSkillCapabilityServiceTest {
    private final ProjectSkillBindingMapper bindings=mock(ProjectSkillBindingMapper.class);
    private final ProjectMapper projects=mock(ProjectMapper.class);
    private final SkillMapper skills=mock(SkillMapper.class);
    private final SkillVersionMapper versions=mock(SkillVersionMapper.class);
    private final AiSkillCapabilityService service=new AiSkillCapabilityService(bindings,projects,skills,versions,new ObjectMapper());

    @BeforeEach void projectExists(){ProjectEntity project=new ProjectEntity();project.setId("p1");when(projects.selectById("p1")).thenReturn(project);when(bindings.selectList(any())).thenReturn(List.of());}

    @Test void savesOnlyCurrentPublishedVersionAndSnapshotsIt() {
        SkillEntity skill=skill("skill-1","sv-1","PUBLISHED"); SkillVersionEntity version=version("sv-1","skill-1","PUBLISHED");
        when(skills.selectById("skill-1")).thenReturn(skill);when(versions.findMeta("sv-1")).thenReturn(version);

        var saved=service.saveProjectSkills("p1",List.of("sv-1"),"member-1");

        assertThat(saved).extracting("versionId").containsExactly("sv-1");
        verify(bindings).deleteByProject("p1");verify(bindings).insert(any(ProjectSkillBindingEntity.class));
        assertThat(service.manifest(new ObjectMapper().valueToTree(saved).toString())).extracting("sha256").containsExactly("abc");
    }

    @Test void rejectsTwoVersionsOfTheSameSkill() {
        SkillEntity skill=skill("skill-1","sv-2","PUBLISHED");
        ProjectSkillBindingEntity retained=new ProjectSkillBindingEntity();retained.setSkillVersionId("sv-1");
        when(bindings.selectList(any())).thenReturn(List.of(retained));
        when(skills.selectById("skill-1")).thenReturn(skill);
        when(versions.findMeta("sv-1")).thenReturn(version("sv-1","skill-1","PUBLISHED"));
        when(versions.findMeta("sv-2")).thenReturn(version("sv-2","skill-1","PUBLISHED"));

        assertThatThrownBy(()->service.saveProjectSkills("p1",List.of("sv-1","sv-2"),"member-1"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("只能绑定一个");
    }

    @Test void auditIsLimitedToTheImmutableManifest() throws Exception {
        var manifest=List.of(new cn.wayuwa.wao.dto.AiSkillCapabilityDto("skill-1","sv-1","代码审查","检查风险","1.0.0","abc",List.of("codex")));
        String json=new ObjectMapper().writeValueAsString(manifest);

        var result=service.normalizeAudit(json,List.of(
                new AiSkillAuditDto("fake","sv-fake","伪造","1.0.0","loaded","不应保存"),
                new AiSkillAuditDto("skill-1","sv-1","被篡改名称","9.9.9","loaded","已加载")));

        assertThat(result).hasSize(1);assertThat(result.get(0).displayName()).isEqualTo("代码审查");
        assertThat(result.get(0).version()).isEqualTo("1.0.0");
    }

    private static SkillEntity skill(String id,String published,String status){SkillEntity value=new SkillEntity();value.setId(id);value.setPublishedVersionId(published);value.setStatus(status);return value;}
    private static SkillVersionEntity version(String id,String skillId,String state){SkillVersionEntity value=new SkillVersionEntity();value.setId(id);value.setSkillId(skillId);value.setState(state);value.setDisplayName("代码审查");value.setSummary("检查风险");value.setVersion(id.equals("sv-1")?"1.0.0":"1.1.0");value.setSha256("abc");value.setCompatiblePlatformsJson("[\"codex\"]");return value;}
}
