package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.dto.NavigationLayoutDto;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class NavigationLayoutServiceTest {
    private final MemberPreferenceMapper mapper = mock(MemberPreferenceMapper.class);
    private final ObjectMapper json = new ObjectMapper();
    private final NavigationLayoutService service = new NavigationLayoutService(mapper, json);
    private final String key = NavigationLayoutService.PREFERENCE_KEY;

    private NavigationLayoutDto layout(String label, String placement) {
        return new NavigationLayoutDto(1, List.of(new NavigationLayoutDto.Group("ROOT", "")),
                List.of(new NavigationLayoutDto.Item("projects", label, "ri-stack-line", "ROOT", 13,
                        true, "TAB", placement)), false);
    }

    @Test void firstSaveKeepsPositionAndIsOwnedByCurrentMember() {
        when(mapper.insertIfAbsent(eq("alice"), eq(key), anyString())).thenReturn(1);
        var saved = service.save("alice", new NavigationLayoutDto.Save("none", layout("我的项目", "TOP")));
        assertThat(saved.layout().items().get(0).placement()).isEqualTo("TOP");
        assertThat(saved.layout().items().get(0).order()).isEqualTo(13);
        assertThat(saved.layout().items().get(0).label()).isEqualTo("我的项目");
        verify(mapper).insertIfAbsent(eq("alice"), eq(key), anyString());
        assertThat(service.get("bob").layout()).isNull();
        verify(mapper).find("bob", key);
    }

    @Test void missingLoginNeverFallsBackToAnotherMembersLayout() {
        assertThatThrownBy(() -> service.get(null)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> service.save("", new NavigationLayoutDto.Save("none", layout("项目", "TOP"))))
                .isInstanceOf(UnauthorizedException.class);
        verifyNoInteractions(mapper);
    }

    @Test void staleAndRacingSavesDoNotOverwriteCurrentLayout() throws Exception {
        String current = json.writeValueAsString(new NavigationLayoutDto.Snapshot("revision-2", layout("当前项目", "DOCK")));
        when(mapper.find("alice", key)).thenReturn(current);
        assertThatThrownBy(() -> service.save("alice", new NavigationLayoutDto.Save("revision-1", layout("旧名称", "MENU"))))
                .isInstanceOf(ConflictException.class);
        verify(mapper, never()).compareAndSet(any(), any(), any(), any());
        when(mapper.compareAndSet(eq("alice"), eq(key), eq(current), anyString())).thenReturn(0);
        assertThatThrownBy(() -> service.save("alice", new NavigationLayoutDto.Save("revision-2", layout("新名称", "TOP"))))
                .isInstanceOf(ConflictException.class);
        verify(mapper).compareAndSet(eq("alice"), eq(key), eq(current), anyString());
    }

    @Test void retryAfterLostResponseReturnsAlreadySavedVersion() throws Exception {
        var content = layout("我的项目", "TOP");
        when(mapper.find("alice", key)).thenReturn(json.writeValueAsString(new NavigationLayoutDto.Snapshot("saved", content)));
        var result = service.save("alice", new NavigationLayoutDto.Save("none", content));
        assertThat(result.revision()).isEqualTo("saved");
        verify(mapper, never()).compareAndSet(any(), any(), any(), any());
        verify(mapper, never()).insertIfAbsent(any(), any(), any());
    }

    @Test void concurrentFirstSaveDoesNotClobberExistingRow() {
        when(mapper.insertIfAbsent(any(), any(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.save("alice", new NavigationLayoutDto.Save("none", layout("项目", "TOP"))))
                .isInstanceOf(ConflictException.class);
        verify(mapper, never()).upsert(any(), any(), any());
    }

    @Test void genericPreferenceWriteCannotBypassNavigationVersionCheck() {
        var member = mock(cn.wayuwa.wao.auth.CurrentMember.class);
        var controller = new cn.wayuwa.wao.controller.MemberPreferenceController(mapper, member);
        assertThatThrownBy(() -> controller.put(key, java.util.Map.of("value", "{}"),
                new org.springframework.mock.web.MockHttpServletRequest()))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(mapper);
    }

    @Test void corruptedLayoutCannotBeOverwrittenAsIfEmpty() {
        when(mapper.find("alice", key)).thenReturn("broken-json");
        assertThatThrownBy(() -> service.get("alice")).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.save("alice", new NavigationLayoutDto.Save("none", layout("项目", "TOP"))))
                .isInstanceOf(ConflictException.class);
        verify(mapper, never()).insertIfAbsent(any(), any(), any());
    }

    @Test void invalidPlacementAndMissingGroupCannotMakeMenusDisappear() {
        assertThatThrownBy(() -> service.save("alice", new NavigationLayoutDto.Save("none", layout("项目", "UNKNOWN"))))
                .isInstanceOf(BadRequestException.class);
        var broken = new NavigationLayoutDto(1, List.of(new NavigationLayoutDto.Group("ROOT", "")),
                List.of(new NavigationLayoutDto.Item("projects", "项目", "ri-stack-line", "missing", 1, true, "TAB", "MENU")), false);
        assertThatThrownBy(() -> service.save("alice", new NavigationLayoutDto.Save("none", broken)))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(mapper);
    }
}
