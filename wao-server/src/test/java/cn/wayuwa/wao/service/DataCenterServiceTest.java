package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.mapper.DataCenterMapper;
import cn.wayuwa.wao.mapper.DataCenterSnapshotRow;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class DataCenterServiceTest {

    private final DataCenterMapper mapper = mock(DataCenterMapper.class);
    private final DataCenterService service = new DataCenterService(mapper);

    @Test
    void buildsStableCatalogFromRealAggregateRows() {
        when(mapper.snapshots()).thenReturn(List.of(
                row("tasks", 12L, LocalDateTime.of(2026, 8, 28, 9, 30)),
                row("projects", 3L, null),
                row("product-updates", 5L, LocalDateTime.of(2026, 8, 28, 10, 0)),
                row("git-repositories", 2L, LocalDateTime.of(2026, 8, 27, 18, 0)),
                row("ai-runs", 0L, null)));

        var overview = service.overview();

        assertThat(overview.sourceCount()).isEqualTo(9);
        assertThat(overview.populatedSourceCount()).isEqualTo(4);
        assertThat(overview.totalRecords()).isEqualTo(22);
        assertThat(overview.sources()).extracting(source -> source.id())
                .containsExactly("projects", "tasks", "ai-runs", "knowledge-documents", "memory-revisions",
                        "shared-chat-messages", "http-request-metrics", "product-updates", "git-repositories");
        assertThat(overview.sources()).filteredOn(source -> "product-updates".equals(source.id()))
                .singleElement().satisfies(source -> {
                    assertThat(source.status()).isEqualTo("READY");
                    assertThat(source.boundary()).contains("不读取作者、diff");
                });
        assertThat(overview.sources()).filteredOn(source -> "projects".equals(source.id()))
                .singleElement().satisfies(source -> assertThat(source.status()).isEqualTo("TIME_UNKNOWN"));
        assertThat(overview.sources()).filteredOn(source -> "tasks".equals(source.id()))
                .singleElement().satisfies(source -> assertThat(source.status()).isEqualTo("READY"));
        assertThat(overview.sources()).filteredOn(source -> "git-repositories".equals(source.id()))
                .singleElement().satisfies(source -> {
                    assertThat(source.status()).isEqualTo("REGISTERED_ONLY");
                    assertThat(source.boundary()).contains("不镜像仓库");
                });
    }

    @Test
    void missingAndEmptyRowsStayEmptyWithoutDemoFallback() {
        when(mapper.snapshots()).thenReturn(List.of(row("tasks", null, null)));

        var overview = service.overview();

        assertThat(overview.totalRecords()).isZero();
        assertThat(overview.populatedSourceCount()).isZero();
        assertThat(overview.sources()).allSatisfy(source -> {
            assertThat(source.recordCount()).isZero();
            assertThat(source.status()).isEqualTo("EMPTY");
        });
    }

    private DataCenterSnapshotRow row(String id, Long count, LocalDateTime latestAt) {
        var row = new DataCenterSnapshotRow();
        row.setSourceId(id);
        row.setRecordCount(count);
        row.setLatestAt(latestAt);
        return row;
    }
}
