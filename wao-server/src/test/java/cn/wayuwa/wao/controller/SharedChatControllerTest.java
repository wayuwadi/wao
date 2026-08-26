package cn.wayuwa.wao.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.dto.CreateSharedChatMessageRequest;
import cn.wayuwa.wao.dto.MemberDto;
import cn.wayuwa.wao.dto.SharedChatMessageDto;
import cn.wayuwa.wao.service.SharedChatService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class SharedChatControllerTest {
    private final SharedChatService service = mock(SharedChatService.class);
    private final CurrentMember currentMember = mock(CurrentMember.class);
    private final SharedChatController controller = new SharedChatController(service, currentMember);
    private final HttpServletRequest http = mock(HttpServletRequest.class);

    @Test
    void derivesAuthorFromServerSessionInsteadOfRequestBody() {
        var body = new CreateSharedChatMessageRequest("client_token_123", "你好", null);
        var message = new SharedChatMessageDto("msg1",
                new MemberDto("m1", "娃哥", "娃", "#6655BE", "管理员"), "你好",
                null, null, null, "SENT", null, LocalDateTime.now());
        when(currentMember.id(http)).thenReturn("m1");
        when(service.create("m1", body)).thenReturn(message);

        var response = controller.create(body, http);

        assertThat(response.data()).isEqualTo(message);
        verify(service).create("m1", body);
    }

    @Test
    void rejectsWriteWhenSessionCannotResolveMember() {
        when(currentMember.id(http)).thenReturn(null);

        assertThatThrownBy(() -> controller.create(
                new CreateSharedChatMessageRequest("client_token_123", "你好", null), http))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("登录已过期");
    }
}
