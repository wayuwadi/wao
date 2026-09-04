package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.domain.TianrenDailyReadingEntity;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.dto.AssistantConversationDto;
import cn.wayuwa.wao.mapper.TianrenDailyReadingMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class TianrenReadingServiceTest {

    @Test void calendarRejectsInvalidOrExcessiveDateRanges() {
        assertThatThrownBy(() -> service.calendar("wg", LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 2))).hasMessageContaining("日期范围");
        assertThatThrownBy(() -> service.calendar("wg", LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1))).hasMessageContaining("日期范围");
    }

    @Test void calendarOnlyReadsSavedRowsWithoutCreatingReading() {
        when(mapper.selectList(any())).thenReturn(java.util.List.of(row()));
        assertThat(service.calendar("wg", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))).hasSize(1);
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never()).insert(any(TianrenDailyReadingEntity.class));
    }

    private final TianrenDailyReadingMapper mapper = mock(TianrenDailyReadingMapper.class);
    private final AiAssistantConversationService conversations = mock(AiAssistantConversationService.class);
    private final TianrenReadingService service = new TianrenReadingService(
            mapper, new TianrenHexagramEngine(), conversations, new ObjectMapper());

    @Test
    void returnsExistingDailyReadingWithoutRecasting() {
        TianrenDailyReadingEntity row = row();
        when(mapper.selectOne(any())).thenReturn(row);

        var reading = service.createToday("wg");

        assertThat(reading.id()).isEqualTo("tr1");
        assertThat(reading.primary().number()).isEqualTo(11);
        assertThat(reading.relating()).isNull();
    }

    @Test
    void createsPrivateConversationAndTrustedCastContextForInterpretation() {
        TianrenDailyReadingEntity row = row();
        when(mapper.selectOne(any())).thenReturn(row);
        when(conversations.create("wg")).thenReturn(new AssistantConversationDto(
                "cv1", "新对话", null, LocalDateTime.now()));

        var prepared = service.prepareInterpretation("wg", "最近总在赶进度");

        assertThat(row.getConversationId()).isEqualTo("cv1");
        assertThat(prepared.request().conversationId()).isEqualTo("cv1");
        assertThat(prepared.request().message()).contains("最近总在赶进度");
        assertThat(prepared.trustedContext())
                .contains("自初爻到上爻", "第 11 卦「泰」", "无变爻");
        verify(mapper).updateById(row);
    }

    @Test
    void createsStableShareTokenOnlyForOwnedReading() {
        TianrenDailyReadingEntity row = row();
        when(mapper.selectOne(any())).thenReturn(row);
        when(mapper.update(isNull(), any())).thenReturn(1);

        String token = service.createShareToken("wg", "tr1");

        assertThat(token).matches("[0-9a-f]{32}");
        verify(mapper).update(isNull(), any());

        row.setShareToken(token);
        assertThat(service.createShareToken("wg", "tr1")).isEqualTo(token);
    }

    @Test
    void rejectsSharingAnotherMembersReading() {
        when(mapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.createShareToken("wg", "tr-other"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("卦象不存在");
    }

    @Test
    void publicProjectionKeepsPrivateIdentityAndConversationOutOfItsType() {
        TianrenDailyReadingEntity row = row();
        row.setConversationId("cv-private");
        row.setShareToken("0123456789abcdef0123456789abcdef");
        when(mapper.selectOne(any())).thenReturn(row);
        when(conversations.latestAssistantReply("cv-private", "wg"))
                .thenReturn("## 四时自照\n\n今天给忙碌留一次停顿。");

        var shared = service.shared(row.getShareToken());

        assertThat(shared.date()).isEqualTo(row.getReadingDate());
        assertThat(shared.primary().name()).isEqualTo("泰");
        assertThat(shared.interpretation()).contains("今天给忙碌留一次停顿");
        assertThat(shared.getClass().getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("id", "memberId", "conversationId", "shareToken");
    }

    @Test
    void rejectsMalformedPublicShareTokensBeforeQueryingStorage() {
        assertThatThrownBy(() -> service.shared("tr1"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("分享链接无效");
    }

    private TianrenDailyReadingEntity row() {
        TianrenDailyReadingEntity row = new TianrenDailyReadingEntity();
        row.setId("tr1");
        row.setMemberId("wg");
        row.setReadingDate(LocalDate.now());
        row.setLinesJson("[7,7,7,8,8,8]");
        row.setPrimaryNumber(11);
        row.setCreatedAt(LocalDateTime.now());
        row.setUpdatedAt(LocalDateTime.now());
        return row;
    }
}
