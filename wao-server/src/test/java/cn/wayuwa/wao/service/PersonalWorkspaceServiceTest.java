package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.PersonalWorkspaceEntity;
import cn.wayuwa.wao.dto.CreateWorkspaceRequest;
import cn.wayuwa.wao.dto.SaveWorkspaceRequest;
import cn.wayuwa.wao.dto.WorkspaceGroupDto;
import cn.wayuwa.wao.dto.WorkspaceLinkDto;
import cn.wayuwa.wao.mapper.PersonalWorkspaceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PersonalWorkspaceServiceTest {

    private final PersonalWorkspaceMapper mapper = mock(PersonalWorkspaceMapper.class);
    private final ProjectService projects = mock(ProjectService.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final PersonalWorkspaceService service = new PersonalWorkspaceService(mapper, projects, json);

    @Test
    void createsOwnedWorkspaceWithDefaultGroupAndNoFakeLinks() throws Exception {
        ArgumentCaptor<PersonalWorkspaceEntity> saved = ArgumentCaptor.forClass(PersonalWorkspaceEntity.class);
        when(mapper.insert(any(PersonalWorkspaceEntity.class))).thenReturn(1);

        var result = service.create(new CreateWorkspaceRequest("  能良工作  ", " 常用环境入口 ", null), "wg");

        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().getOwnerMemberId()).isEqualTo("wg");
        assertThat(saved.getValue().getName()).isEqualTo("能良工作");
        assertThat(json.readTree(saved.getValue().getContentJson()).path("groups")).hasSize(1);
        assertThat(result.groups()).singleElement().satisfies(group -> {
            assertThat(group.name()).isEqualTo("常用");
            assertThat(group.links()).isEmpty();
        });
    }

    @Test
    void canonicalizesOrderAndAcceptsInternalHttpEnvironment() {
        PersonalWorkspaceEntity existing = workspace();
        when(mapper.findOwned("ws1", "wg")).thenReturn(existing);
        when(mapper.updateOwned(eq("ws1"), eq("wg"), any(), any(), any(), any(), any())).thenReturn(1);

        WorkspaceLinkDto link = new WorkspaceLinkDto("", " Nacos 测试环境 ",
                "http://192.168.1.8:8848/nacos", " 服务发现 ", " 测试 ", "bad icon", 99, true);
        WorkspaceGroupDto group = new WorkspaceGroupDto("", " 环境 ", "ri-server-line", 88, List.of(link));
        service.save("ws1", new SaveWorkspaceRequest("空间", "", null, List.of(group)), "wg");

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(mapper).updateOwned(eq("ws1"), eq("wg"), eq(null), eq("空间"), eq(""), content.capture(), any());
        assertThat(content.getValue()).contains("http://192.168.1.8:8848/nacos");
        assertThat(content.getValue()).contains("\"order\":0");
        assertThat(content.getValue()).contains("ri-links-line");
    }

    @Test
    void rejectsExecutableOrCredentialBearingUrls() {
        PersonalWorkspaceEntity existing = workspace();
        when(mapper.findOwned("ws1", "wg")).thenReturn(existing);
        WorkspaceGroupDto group = new WorkspaceGroupDto("wg1", "环境", "ri-folder-line", 0,
                List.of(new WorkspaceLinkDto("wl1", "危险链接", "javascript:alert(1)",
                        "", "", "ri-links-line", 0, false)));

        assertThatThrownBy(() -> service.save("ws1",
                new SaveWorkspaceRequest("空间", "", null, List.of(group)), "wg"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("http://");
        verify(mapper, never()).updateOwned(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void hidesAnotherMembersWorkspaceAsNotFound() {
        when(mapper.findOwned("ws-other", "wg")).thenReturn(null);

        assertThatThrownBy(() -> service.get("ws-other", "wg"))
                .isInstanceOf(NotFoundException.class).hasMessageContaining("不存在");
    }

    private PersonalWorkspaceEntity workspace() {
        PersonalWorkspaceEntity entity = new PersonalWorkspaceEntity();
        entity.setId("ws1");
        entity.setOwnerMemberId("wg");
        entity.setName("空间");
        entity.setDescriptionText("");
        entity.setContentJson("{\"version\":1,\"groups\":[]}");
        entity.setCreatedAt(LocalDateTime.of(2026, 8, 18, 10, 0));
        entity.setUpdatedAt(LocalDateTime.of(2026, 8, 18, 10, 0));
        return entity;
    }
}
