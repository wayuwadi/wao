package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.TextPolishRecordEntity;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import cn.wayuwa.wao.mapper.TextPolishRecordMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class TextPolishServiceTest {
    private final MemberPreferenceMapper preferences = Mockito.mock(MemberPreferenceMapper.class);
    private final TextPolishRecordMapper records = Mockito.mock(TextPolishRecordMapper.class);
    private final AiAssistantService assistant = Mockito.mock(AiAssistantService.class);
    private final TextPolishService service = new TextPolishService(preferences, records, assistant);

    @BeforeEach
    void emptyHistory() {
        when(records.listOwned(any())).thenReturn(List.of());
    }

    @Test
    void returnsProductDefaultWithoutWritingFakeHistory() {
        var workspace = service.workspace("wg");

        assertThat(workspace.prompt()).contains("看明白", "想明白", "说明白", "干明白");
        assertThat(workspace.records()).isEmpty();
        verify(records, never()).insert(any(TextPolishRecordEntity.class));
    }

    @Test
    void savesTrimmedMemberPromptAndRejectsBlankValue() {
        assertThat(service.savePrompt("wg", "  按结论、原因、步骤整理。  "))
                .isEqualTo("按结论、原因、步骤整理。");
        verify(preferences).upsert("wg", TextPolishService.PROMPT_PREFERENCE_KEY,
                "按结论、原因、步骤整理。");

        assertThatThrownBy(() -> service.savePrompt("wg", "   "))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("不能为空");
    }

    @Test
    void storesPromptSnapshotAndBeforeAfterContentOnlyAfterRealAiSuccess() {
        when(preferences.find("wg", TextPolishService.PROMPT_PREFERENCE_KEY)).thenReturn("先结论后步骤");
        when(assistant.polishTextWithPrompt("原来这个事情吧比较绕。", "wg", "先结论后步骤"))
                .thenReturn("结论：事情较复杂。\n\n步骤：先梳理背景，再确定行动。");

        var result = service.run("wg", "  原来这个事情吧比较绕。  ");

        assertThat(result.originalText()).isEqualTo("原来这个事情吧比较绕。");
        assertThat(result.polishedText()).contains("结论", "步骤");
        assertThat(result.promptSnapshot()).isEqualTo("先结论后步骤");
        ArgumentCaptor<TextPolishRecordEntity> record = ArgumentCaptor.forClass(TextPolishRecordEntity.class);
        verify(records).insert(record.capture());
        assertThat(record.getValue().getMemberId()).isEqualTo("wg");
        assertThat(record.getValue().getPromptSnapshot()).isEqualTo("先结论后步骤");
        assertThat(record.getValue().getOriginalText()).isEqualTo("原来这个事情吧比较绕。");
        assertThat(record.getValue().getPolishedText()).contains("先梳理背景");
    }

    @Test
    void aiFailureKeepsHistoryUnchanged() {
        when(assistant.polishTextWithPrompt(any(), any(), any()))
                .thenThrow(new BadRequestException("本地 Codex 暂时离线"));

        assertThatThrownBy(() -> service.run("wg", "需要润色的原文"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("暂时离线");
        verify(records, never()).insert(any(TextPolishRecordEntity.class));
    }

    @Test
    void historyQueryAlwaysScopesCurrentMember() {
        service.history("wg");

        verify(records).listOwned("wg");
    }

    @Test
    void asynchronousResultUsesStableIdAndDoesNotDuplicateHistory() {
        var created = service.recordResult("tp-stable", "wg", "先结论", "原文", "结果");

        assertThat(created.id()).isEqualTo("tp-stable");
        verify(records).insert(any(TextPolishRecordEntity.class));

        TextPolishRecordEntity existing = new TextPolishRecordEntity();
        existing.setId("tp-stable");
        existing.setMemberId("wg");
        existing.setPromptSnapshot("先结论");
        existing.setOriginalText("原文");
        existing.setPolishedText("结果");
        existing.setCreatedAt(java.time.LocalDateTime.now());
        when(records.selectById("tp-stable")).thenReturn(existing);

        assertThat(service.recordResult("tp-stable", "wg", "先结论", "原文", "结果").id())
                .isEqualTo("tp-stable");
        verify(records, org.mockito.Mockito.times(1)).insert(any(TextPolishRecordEntity.class));
    }
}
