package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;

import cn.wayuwa.wao.domain.AiQuotaSnapshotEntity;
import cn.wayuwa.wao.dto.AiSessionQuotaDto;
import cn.wayuwa.wao.dto.CodexQuotaCreditsDto;
import cn.wayuwa.wao.dto.CodexQuotaLimitDto;
import cn.wayuwa.wao.dto.CodexQuotaWindowDto;
import cn.wayuwa.wao.dto.RunnerCodexQuotaReport;
import cn.wayuwa.wao.mapper.AiQuotaSnapshotMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AiQuotaSnapshotServiceTest {

    private final AiQuotaSnapshotMapper mapper = mock(AiQuotaSnapshotMapper.class);
    private final AiQuotaSnapshotService service = new AiQuotaSnapshotService(mapper);

    @Test
    void centralSnapshotStoresRemainingQuotaWithoutProviderPayload() {
        LocalDateTime observed = LocalDateTime.of(2026, 8, 31, 10, 0);
        service.recordCentral("aa1", "codex", "Codex 主账号",
                new AiSessionQuotaDto("aa1", "codex", "Codex 主账号", "pro",
                        35, 65, "2026-08-31T12:00:00", observed.toString(), "SYNCED", "真实同步"),
                null, observed);

        ArgumentCaptor<AiQuotaSnapshotEntity> saved = ArgumentCaptor.forClass(AiQuotaSnapshotEntity.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().getSourceKey()).isEqualTo("central:aa1");
        assertThat(saved.getValue().getRemainingPercent()).isEqualTo(65);
        assertThat(saved.getValue().getWindowKey()).isEqualTo("session");
        assertThat(saved.getValue().getObservedAt()).isEqualTo(observed);
    }

    @Test
    void runnerSnapshotConvertsUsedPercentAndKeepsOnlySanitizedFields() {
        LocalDateTime observed = LocalDateTime.of(2026, 8, 31, 10, 5);
        RunnerCodexQuotaReport report = new RunnerCodexQuotaReport("WAGE@example.com", "pro",
                new CodexQuotaCreditsDto(false, false, null),
                List.of(new CodexQuotaLimitDto("codex", "通用使用限额",
                        List.of(new CodexQuotaWindowDto(28, 10_080L, 1_788_192_000L)))));

        service.recordRunner("rn1", report, observed);

        ArgumentCaptor<AiQuotaSnapshotEntity> saved = ArgumentCaptor.forClass(AiQuotaSnapshotEntity.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().getSourceKey()).isEqualTo("runner-account:wage@example.com");
        assertThat(saved.getValue().getRemainingPercent()).isEqualTo(72);
        assertThat(saved.getValue().getWindowLabel()).contains("七天");
    }
}
