package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.domain.TianrenDailyReadingEntity;
import cn.wayuwa.wao.dto.AssistantConversationDto;
import cn.wayuwa.wao.mapper.TianrenDailyReadingMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class TianrenReadingServiceTest {

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
