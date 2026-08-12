package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.VocabularyEntryEntity;
import cn.wayuwa.wao.dto.CaptureVocabularyEntryRequest;
import cn.wayuwa.wao.dto.UpdateVocabularyEntryRequest;
import cn.wayuwa.wao.mapper.VocabularyEntryMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class VocabularyEntryServiceTest {

    private final VocabularyEntryMapper mapper = mock(VocabularyEntryMapper.class);
    private final VocabularyEntryService service = new VocabularyEntryService(mapper);

    @Test
    void capturesCaseInsensitiveDuplicateAsAnotherEncounter() {
        VocabularyEntryEntity stored = entry();
        stored.setEncounterCount(2);
        when(mapper.capture(any(), eq("wg"), eq("Resilient"), eq("resilient"), any())).thenReturn(1);
        when(mapper.findByNormalizedTerm("wg", "resilient")).thenReturn(stored);

        var result = service.capture(new CaptureVocabularyEntryRequest("  Resilient  "), "wg");

        assertThat(result.created()).isFalse();
        assertThat(result.entry().encounterCount()).isEqualTo(2);
        verify(mapper).capture(any(), eq("wg"), eq("Resilient"), eq("resilient"), any());
    }

    @Test
    void rejectsContentWithoutEnglishLetters() {
        assertThatThrownBy(() -> service.capture(new CaptureVocabularyEntryRequest("韧性"), "wg"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("英文字母");
    }

    @Test
    void updatesOnlyOwnedEntryAndTrimsNotes() {
        when(mapper.updateOwned(eq("ve1"), eq("wg"), eq("韧性的"), eq("work docs"),
                eq("MASTERED"), any())).thenReturn(1);
        VocabularyEntryEntity saved = entry();
        saved.setMeaningText("韧性的");
        saved.setContextText("work docs");
        saved.setLearningState("MASTERED");
        when(mapper.findOwned("ve1", "wg")).thenReturn(saved);

        var result = service.update("ve1", new UpdateVocabularyEntryRequest(
                "  韧性的  ", "  work docs  ", "mastered"), "wg");

        assertThat(result.meaning()).isEqualTo("韧性的");
        assertThat(result.state()).isEqualTo("MASTERED");
    }

    @Test
    void reviewingCountsOnceAndPersistsSelfAssessment() {
        when(mapper.reviewOwned(eq("ve1"), eq("wg"), eq("LEARNING"), any())).thenReturn(1);
        VocabularyEntryEntity saved = entry();
        saved.setReviewCount(3);
        when(mapper.findOwned("ve1", "wg")).thenReturn(saved);

        var result = service.review("ve1", "LEARNING", "wg");

        assertThat(result.reviewCount()).isEqualTo(3);
        ArgumentCaptor<LocalDateTime> time = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).reviewOwned(eq("ve1"), eq("wg"), eq("LEARNING"), time.capture());
        assertThat(time.getValue()).isNotNull();
    }

    @Test
    void hidesOtherMembersEntryAsNotFound() {
        when(mapper.updateOwned(eq("ve-other"), eq("wg"), any(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.update("ve-other",
                new UpdateVocabularyEntryRequest("", "", "LEARNING"), "wg"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("不存在");
    }

    private VocabularyEntryEntity entry() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 12, 12, 0);
        VocabularyEntryEntity entry = new VocabularyEntryEntity();
        entry.setId("ve1");
        entry.setOwnerMemberId("wg");
        entry.setTerm("Resilient");
        entry.setNormalizedTerm("resilient");
        entry.setMeaningText("");
        entry.setContextText("");
        entry.setLearningState("LEARNING");
        entry.setEncounterCount(1);
        entry.setReviewCount(0);
        entry.setLastEncounteredAt(now);
        entry.setCreatedAt(now);
        entry.setUpdatedAt(now);
        return entry;
    }
}
