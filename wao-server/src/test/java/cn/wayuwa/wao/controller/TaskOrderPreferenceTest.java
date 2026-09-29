package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class TaskOrderPreferenceTest {
    @Test void personalOrdersUseAuthenticatedOwnerAndKeepOtherPreferenceLimit() {
        var mapper = mock(MemberPreferenceMapper.class);
        var member = mock(CurrentMember.class);
        var request = new MockHttpServletRequest();
        when(member.idOr(request, "wg")).thenReturn("member-a");
        var controller = new MemberPreferenceController(mapper, member);
        String large = "x".repeat(5000);
        controller.put("task-order-v1:project:p", Map.of("value", large), request);
        verify(mapper).upsert("member-a", "task-order-v1:project:p", large);
        controller.put("project-layout-v1:overview", Map.of("value", large), request);
        verify(mapper).upsert("member-a", "project-layout-v1:overview", large);
        assertThatThrownBy(() -> controller.put("other", Map.of("value", large), request)).hasMessageContaining("4000");
        assertThatThrownBy(() -> controller.put("task-order-v1:project:p", Map.of("value", "x".repeat(60001)), request)).hasMessageContaining("60000");
        assertThatThrownBy(() -> controller.put("project-layout-v1:overview", Map.of("value", "x".repeat(60001)), request)).hasMessageContaining("60000");
        controller.get("task-order-v1:project:p", request);
        verify(mapper).find("member-a", "task-order-v1:project:p");
    }
}
