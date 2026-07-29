package cn.wayuwa.wao.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.dto.AiAccountDto;
import cn.wayuwa.wao.dto.AiAccountFormRequest;
import cn.wayuwa.wao.dto.AiAccountLoginStatusDto;
import cn.wayuwa.wao.dto.AiAccountSyncResultDto;
import cn.wayuwa.wao.service.AiAccountService;
import org.junit.jupiter.api.Test;

class AiAccountControllerTest {

    private final AiAccountService service = mock(AiAccountService.class);
    private final CurrentMember currentMember = mock(CurrentMember.class);
    private final AiAccountController controller = new AiAccountController(service, currentMember);

    /** 账号 DTO 字段多，测试只关心 id/provider/label，其余给默认值。 */
    private static AiAccountDto account(String id) {
        return new AiAccountDto(id, "claude", "A", null, true, "manual", null,
                "UNKNOWN", null, null, null, null, null, null, null, null);
    }

    @Test
    void listReturnsServiceResult() {
        List<AiAccountDto> expected = List.of(account("aa-1"));
        when(service.list()).thenReturn(expected);

        assertThat(controller.list().data()).isEqualTo(expected);
    }

    @Test
    void createDelegatesToService() {
        AiAccountFormRequest request = new AiAccountFormRequest("claude", "A", null, "{}", true);
        AiAccountDto expected = account("aa-1");
        when(service.create(request)).thenReturn(expected);

        assertThat(controller.create(request).data()).isEqualTo(expected);
    }

    @Test
    void updateDelegatesToService() {
        AiAccountFormRequest request = new AiAccountFormRequest("claude", "A", null, "{}", true);
        AiAccountDto expected = account("aa-1");
        when(service.update("aa-1", request)).thenReturn(expected);

        assertThat(controller.update("aa-1", request).data()).isEqualTo(expected);
    }

    @Test
    void deleteDelegatesToService() {
        assertThat(controller.delete("aa-1").code()).isZero();
        verify(service).delete("aa-1");
    }

    @Test
    void syncDelegatesToService() {
        AiAccountSyncResultDto expected = new AiAccountSyncResultDto("aa-1", "claude",
                "NO_API", "无可用接口", null, null);
        when(service.sync("aa-1")).thenReturn(expected);

        assertThat(controller.sync("aa-1").data()).isEqualTo(expected);
    }

    @Test
    void loginStatusDelegatesToDeviceLoginService() {
        AiAccountLoginStatusDto expected = new AiAccountLoginStatusDto("pending", "等待授权", null);
        when(service.loginStatus("state-1")).thenReturn(expected);

        assertThat(controller.loginStatus("state-1").data()).isEqualTo(expected);
    }
}
