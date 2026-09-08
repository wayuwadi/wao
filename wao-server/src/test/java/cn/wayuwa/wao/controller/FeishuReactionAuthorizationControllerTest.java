package cn.wayuwa.wao.controller;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;
import cn.wayuwa.wao.auth.*;
import cn.wayuwa.wao.service.*;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.mock.web.MockHttpSession;

class FeishuReactionAuthorizationControllerTest {
    private final FeishuReactionAuthorizationService grants = mock(FeishuReactionAuthorizationService.class);
    private final FeishuReactionAuthorizationController reactions = new FeishuReactionAuthorizationController(grants);
    private final FeishuLoginService login = mock(FeishuLoginService.class);
    private final AccountAuthService accounts = mock(AccountAuthService.class);
    private final AuthSessionManager sessions = mock(AuthSessionManager.class);
    private final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders.standaloneSetup(reactions,
            new FeishuLoginController(login, accounts, sessions, reactions)).build();

    @Test void entryExplainsPersonalConsentAndOffersDirectAuthorizationWithoutSendingMessages() throws Exception {
        mvc.perform(get("/api/auth/feishu/reactions")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(containsString("使用飞书授权个人表情")))
                .andExpect(content().string(containsString("表情作者会显示为你本人")));
        verifyNoInteractions(grants, accounts, sessions);
    }
    @Test void personalCallbackEscapesNameAndDoesNotChangeWaoLogin() throws Exception {
        var session = new MockHttpSession();
        when(grants.complete(session, "reaction.state", "code")).thenReturn("娃哥<script>alert(1)</script>");
        mvc.perform(get("/api/auth/feishu/callback").session(session).param("state", "reaction.state").param("code", "code"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("个人表情已授权")))
                .andExpect(content().string(not(containsString("<script>"))))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
        verifyNoInteractions(login, accounts, sessions);
    }
    @Test void declinedAuthorizationOffersRetryWithoutSuccessClaim() throws Exception {
        when(grants.complete(any(), eq("reaction.state"), isNull())).thenThrow(new IllegalStateException("你尚未同意授权"));
        mvc.perform(get("/api/auth/feishu/callback").param("state", "reaction.state").param("error", "access_denied"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("授权未完成")))
                .andExpect(content().string(not(containsString("个人表情已授权"))));
    }
    @Test void existingLoginCallbackKeepsItsRedirectFlow() throws Exception {
        when(login.callbackUri("code", "login-state")).thenReturn(URI.create("https://wao.test/auth/feishu/callback?code=code"));
        mvc.perform(get("/api/auth/feishu/callback").param("state", "login-state").param("code", "code"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("https://wao.test/auth/feishu/callback?code=code"));
        verifyNoInteractions(grants, accounts, sessions);
    }
}
