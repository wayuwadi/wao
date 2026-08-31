package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.common.BadRequestException;
import org.junit.jupiter.api.Test;

class FeishuQuickCollectReactionPolicyTest {

    private final SettingService settings = mock(SettingService.class);
    private final FeishuQuickCollectReactionPolicy policy = new FeishuQuickCollectReactionPolicy(settings);

    @Test
    void defaultsMatchGetToDoneWorkflow() {
        assertThat(policy.receivedEmoji()).isEqualTo("GET");
        assertThat(policy.completedEmoji()).isEqualTo("DONE");
    }

    @Test
    void supportedWorkflowCanBeSaved() {
        policy.save("StatusInFlight", "CheckMark");

        verify(settings).put(SettingService.QUICK_COLLECT_RECEIVED_EMOJI, "StatusInFlight");
        verify(settings).put(SettingService.QUICK_COLLECT_COMPLETED_EMOJI, "CheckMark");
    }

    @Test
    void unsupportedEmojiIsRejectedInsteadOfSilentlyBreakingFeishu() {
        assertThatThrownBy(() -> policy.save("NOT_REAL", "DONE"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("不受支持");
    }

    @Test
    void invalidStoredValueFallsBackToSafeDefault() {
        when(settings.get(SettingService.QUICK_COLLECT_RECEIVED_EMOJI, "GET")).thenReturn("INVALID");

        assertThat(policy.receivedEmoji()).isEqualTo("GET");
    }
}
