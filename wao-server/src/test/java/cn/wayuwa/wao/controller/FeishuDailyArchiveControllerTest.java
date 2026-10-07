package cn.wayuwa.wao.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.auth.FeishuBotAdminInterceptor;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.GlobalExceptionHandler;
import cn.wayuwa.wao.domain.MemberEntity;
import cn.wayuwa.wao.dto.FeishuDailyArchiveDto;
import cn.wayuwa.wao.mapper.MemberMapper;
import cn.wayuwa.wao.service.FeishuDailyArchiveService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FeishuDailyArchiveControllerTest {
    @Test
    void deviceTokensAndMembersCannotReadOrChangeGlobalArchiveAndAdminCasReturns409() throws Exception {
        var service = mock(FeishuDailyArchiveService.class);
        var current = mock(CurrentMember.class); var members = mock(MemberMapper.class);
        var mvc = MockMvcBuilders.standaloneSetup(new FeishuDailyArchiveController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(new FeishuBotAdminInterceptor(current, members)).build();
        mvc.perform(get("/api/feishu/daily-archive").header("Authorization", "Bearer device-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/feishu/daily-archive").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"expectedEnabled\":false}")).andExpect(status().isUnauthorized());
        when(current.id(any())).thenReturn("member");
        var member = new MemberEntity(); member.setRole("成员"); when(members.selectById("member")).thenReturn(member);
        mvc.perform(get("/api/feishu/daily-archive")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
        member.setRole("管理员");
        when(service.state()).thenReturn(new FeishuDailyArchiveDto(false, "Asia/Shanghai", "0 0 0 * * *", null,
                true, "app", "chat", "群聊", "kb", null));
        mvc.perform(get("/api/feishu/daily-archive")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false)).andExpect(jsonPath("$.data.timezone").value("Asia/Shanghai"));
        mvc.perform(put("/api/feishu/daily-archive").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(status().isBadRequest());
        when(service.configure(true, false)).thenThrow(new ConflictException("状态已更新"));
        mvc.perform(put("/api/feishu/daily-archive").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"expectedEnabled\":false}")).andExpect(status().isConflict());
        verify(service).configure(true, false);
    }
}
