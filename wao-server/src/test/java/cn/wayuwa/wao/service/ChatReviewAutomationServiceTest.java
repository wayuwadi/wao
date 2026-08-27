package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.ChatReviewAutoConfigEntity;
import cn.wayuwa.wao.mapper.ChatReviewAutoConfigMapper;
import org.junit.jupiter.api.Test;

class ChatReviewAutomationServiceTest {
    private final ChatReviewAutoConfigMapper mapper = mock(ChatReviewAutoConfigMapper.class);
    private final ChatReviewService reviews = mock(ChatReviewService.class);
    private final AiAssistantService assistant = mock(AiAssistantService.class);
    private final ChatReviewAutomationService service =
            new ChatReviewAutomationService(mapper, reviews, assistant);

    @Test
    void savesValidatedIntervalRangeAndCapabilityAsOnePolicy() {
        when(assistant.validateCapability("aa-deepseek")).thenReturn("aa-deepseek");
        when(mapper.save(true, 3600, "MESSAGES", 40, "只看分歧", "aa-deepseek", "wg"))
                .thenReturn(1);
        when(mapper.selectById(1)).thenReturn(config());

        assertThat(service.save(true, 3600, "messages", 40, " 只看分歧 ", "aa-deepseek", "wg")
                .aiCapabilityId()).isEqualTo("aa-deepseek");
        verify(assistant).validateCapability("aa-deepseek");
    }

    @Test
    void rejectsIntervalsThatCouldCreateAnAccidentalRequestStorm() {
        assertThatThrownBy(() -> service.save(true, 60, "MESSAGES", 20, null, "local-codex", "wg"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("5 分钟");
    }

    @Test
    void onlyTheDatabaseLeaseWinnerCreatesAnAutomaticReview() {
        when(mapper.claimDue()).thenReturn(1, 0);
        when(mapper.selectById(1)).thenReturn(config());
        when(reviews.createAutomatic("MESSAGES", 40, "只看分歧", "aa-deepseek"))
                .thenReturn("cr-auto");

        assertThat(service.triggerDue()).isTrue();
        assertThat(service.triggerDue()).isFalse();
        verify(mapper).recordRun("cr-auto");
    }

    private static ChatReviewAutoConfigEntity config() {
        ChatReviewAutoConfigEntity row = new ChatReviewAutoConfigEntity();
        row.setId(1);
        row.setEnabled(true);
        row.setIntervalSeconds(3600);
        row.setRangeKind("MESSAGES");
        row.setRangeValue(40);
        row.setFocusText("只看分歧");
        row.setAiCapabilityId("aa-deepseek");
        return row;
    }
}
