package cn.wayuwa.wao.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.domain.MemberEntity;
import cn.wayuwa.wao.mapper.MemberMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class FeishuBotAdminInterceptorTest {
    @Test
    void dailyArchiveReadAndWriteRoutesUseTheSameSessionAdministratorGuard() {
        class Registry extends org.springframework.web.servlet.config.annotation.InterceptorRegistry {
            java.util.List<Object> registered() { return getInterceptors(); }
        }
        var guard = new FeishuBotAdminInterceptor(mock(CurrentMember.class), mock(MemberMapper.class));
        var registry = new Registry(); guard.addInterceptors(registry);
        var mapped = (org.springframework.web.servlet.handler.MappedInterceptor) registry.registered().get(0);
        assertThat(mapped.getPathPatterns()).contains("/api/feishu/daily-archive", "/api/feishu/daily-archive/**");
    }

    @Test
    void acceptsOnlyPersistedAdministratorSessionRole() {
        var current = mock(CurrentMember.class); var members = mock(MemberMapper.class);
        var guard = new FeishuBotAdminInterceptor(current, members);
        var request = new MockHttpServletRequest(); var response = new MockHttpServletResponse();
        request.addHeader("Authorization", "Bearer device-token");
        assertThatThrownBy(() -> guard.preHandle(request, response, new Object())).isInstanceOf(UnauthorizedException.class);
        when(current.id(request)).thenReturn("member1");
        var member = new MemberEntity(); member.setRole("成员"); when(members.selectById("member1")).thenReturn(member);
        assertThatThrownBy(() -> guard.preHandle(request, response, new Object())).isInstanceOf(UnauthorizedException.class);
        member.setRole("管理员"); assertThat(guard.preHandle(request, response, new Object())).isTrue();
        member.setRole("超级管理员"); assertThat(guard.preHandle(request, response, new Object())).isTrue();
    }
}
