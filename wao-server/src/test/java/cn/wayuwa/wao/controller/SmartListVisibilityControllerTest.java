package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.domain.MemberPreferenceEntity;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SmartListVisibilityControllerTest {
    @Test void readsOnlyTheAuthenticatedMembersChoicesAndUsesAtomicWrites() {
        var mapper = mock(MemberPreferenceMapper.class);
        var member = mock(CurrentMember.class);
        var request = new MockHttpServletRequest();
        when(member.id(request)).thenReturn("member-a");
        var controller = new SmartListVisibilityController(mapper, member);
        var row = new MemberPreferenceEntity(); row.setPrefKey("tsv1:project-a"); row.setPrefValue("0");
        when(mapper.findRange("member-a", "tsv1:", "tsv1:\uffff")).thenReturn(List.of(row));
        assertThat(controller.list(request).data()).containsEntry("project-a", "0");
        when(mapper.insertIfAbsent("member-a", "tsv1:project-b", "0")).thenReturn(1);
        controller.change("project-b", new SmartListVisibilityController.Change(false, ""), request);
        verify(mapper).insertIfAbsent("member-a", "tsv1:project-b", "0");
        when(mapper.find("member-a", "tsv1:project-a")).thenReturn("0");
        when(mapper.compareAndSet("member-a", "tsv1:project-a", "0", "1")).thenReturn(1);
        controller.change("project-a", new SmartListVisibilityController.Change(true, "0"), request);
        verify(mapper).compareAndSet("member-a", "tsv1:project-a", "0", "1");
    }

    @Test void detectsConflictsAllowsIdempotentRetryAndRequiresLogin() {
        var mapper = mock(MemberPreferenceMapper.class);
        var member = mock(CurrentMember.class);
        var request = new MockHttpServletRequest();
        var controller = new SmartListVisibilityController(mapper, member);
        assertThatThrownBy(() -> controller.list(request)).isInstanceOf(UnauthorizedException.class);
        when(member.id(request)).thenReturn("member-a");
        when(mapper.find("member-a", "tsv1:a")).thenReturn("1");
        assertThatThrownBy(() -> controller.change("a", new SmartListVisibilityController.Change(false, ""), request))
                .isInstanceOf(ConflictException.class);
        controller.change("a", new SmartListVisibilityController.Change(true, ""), request);
        verify(mapper, never()).upsert(anyString(), anyString(), anyString());
        assertThatThrownBy(() -> controller.change("invalid/id", new SmartListVisibilityController.Change(false, ""), request))
                .hasMessageContaining("格式");
    }

    @Test void concurrentFirstWriteConflictsAndLegacyPreferenceEndpointCannotBypassProtection() {
        var mapper = mock(MemberPreferenceMapper.class);
        var member = mock(CurrentMember.class);
        var request = new MockHttpServletRequest();
        when(member.id(request)).thenReturn("member-a");
        var controller = new SmartListVisibilityController(mapper, member);
        when(mapper.insertIfAbsent("member-a", "tsv1:a", "0")).thenReturn(0);
        assertThatThrownBy(() -> controller.change("a", new SmartListVisibilityController.Change(false, ""), request))
                .isInstanceOf(ConflictException.class);
        var legacy = new MemberPreferenceController(mapper, member);
        assertThatThrownBy(() -> legacy.put("tsv1:a", java.util.Map.of("value", "0"), request))
                .hasMessageContaining("智能清单显示设置接口");
    }

    @Test void nestedRoutesAcceptTheDocumentedJsonContract() throws Exception {
        var mapper = mock(MemberPreferenceMapper.class);
        var member = mock(CurrentMember.class);
        when(member.id(any())).thenReturn("member-a");
        when(mapper.findRange(eq("member-a"), anyString(), anyString())).thenReturn(List.of());
        when(mapper.insertIfAbsent("member-a", "tsv1:a", "0")).thenReturn(1);
        var mvc = MockMvcBuilders.standaloneSetup(new SmartListVisibilityController(mapper, member),
                new MemberPreferenceController(mapper, member)).build();
        mvc.perform(get("/api/preferences/smart-list/items"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isMap());
        mvc.perform(put("/api/preferences/smart-list/items/a").contentType("application/json")
                        .content("{\"visible\":false,\"expected\":\"\"}"))
                .andExpect(status().isOk());
        verify(mapper).insertIfAbsent("member-a", "tsv1:a", "0");
    }
}
