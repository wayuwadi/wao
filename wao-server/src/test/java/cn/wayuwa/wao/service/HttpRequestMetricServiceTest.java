package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.HttpRequestMetricEntity;
import cn.wayuwa.wao.dto.RequestMetricInput;
import cn.wayuwa.wao.mapper.HttpRequestMetricMapper;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HttpRequestMetricServiceTest {

    private final HttpRequestMetricMapper mapper = mock(HttpRequestMetricMapper.class);
    private final HttpRequestMetricService service = new HttpRequestMetricService(mapper);

    @Test
    void bindsBatchToServerMemberAndStoresOnlySanitizedFields() {
        RequestMetricInput input = new RequestMetricInput(
                "get", "/api/projects", 200, 1_234, "4g", 1, OffsetDateTime.now().minusSeconds(2));

        assertThat(service.record("member-a", List.of(input))).isEqualTo(1);

        ArgumentCaptor<List<HttpRequestMetricEntity>> captured = ArgumentCaptor.forClass(List.class);
        verify(mapper).insertBatch(captured.capture());
        assertThat(captured.getValue()).singleElement().satisfies(metric -> {
            assertThat(metric.getMemberId()).isEqualTo("member-a");
            assertThat(metric.getMethod()).isEqualTo("GET");
            assertThat(metric.getRequestPath()).isEqualTo("/api/projects");
            assertThat(metric.getDurationMs()).isEqualTo(1_234);
            assertThat(metric.getRetryAttempt()).isEqualTo(1);
        });
    }

    @Test
    void validatesWholeBatchBeforeOneWriteAndSkipsEmptyBatches() {
        var valid = new RequestMetricInput("GET", "/api/projects", 200, 20, null, 0, OffsetDateTime.now());
        var invalid = new RequestMetricInput("GET", "/api/tasks?secret=value", 200, 20, null, 0, OffsetDateTime.now());
        assertThat(service.record("member-a", List.of())).isZero();
        assertThatThrownBy(() -> service.record("member-a", List.of(valid, invalid)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.record("member-a", java.util.Collections.nCopies(101, valid)))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(mapper);
        var batch = java.util.Collections.nCopies(100, valid);
        assertThat(service.record("member-a", batch)).isEqualTo(100);
        ArgumentCaptor<List<HttpRequestMetricEntity>> captured = ArgumentCaptor.forClass(List.class);
        verify(mapper).insertBatch(captured.capture());
        assertThat(captured.getValue()).hasSize(100).allSatisfy(metric ->
                assertThat(metric.getMemberId()).isEqualTo("member-a"));
    }

    @Test
    void rejectsQueriesMonitoringRecursionAndStaleClientTimes() {
        assertThatThrownBy(() -> service.record("member-a", List.of(new RequestMetricInput(
                "GET", "/api/projects?owner=secret", 200, 10, null, 0, OffsetDateTime.now()))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("path");
        assertThatThrownBy(() -> service.record("member-a", List.of(new RequestMetricInput(
                "POST", "/api/request-metrics/batch", 200, 10, null, 0, OffsetDateTime.now()))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("path");
        assertThatThrownBy(() -> service.record("member-a", List.of(new RequestMetricInput(
                "GET", "/api/projects", 200, 10, null, 0, OffsetDateTime.now().minusDays(31)))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("时间");
    }

    @Test
    void aggregatesOwnedWindowWithExactP95SlowAndFailureCounts() {
        when(mapper.listOwnedSince(eq("member-a"), any(LocalDateTime.class))).thenReturn(List.of(
                metric(3, "GET", "/api/vault/groups", null, 3_000),
                metric(2, "GET", "/api/projects", 200, 1_000),
                metric(1, "GET", "/api/projects", 200, 100)));

        var overview = service.overview("member-a", 24);

        verify(mapper).listOwnedSince(eq("member-a"), any(LocalDateTime.class));
        assertThat(overview.totalRequests()).isEqualTo(3);
        assertThat(overview.averageDurationMs()).isEqualTo(1_367);
        assertThat(overview.p95DurationMs()).isEqualTo(3_000);
        assertThat(overview.slowRequests()).isEqualTo(2);
        assertThat(overview.failedRequests()).isEqualTo(1);
        assertThat(overview.endpoints()).extracting(item -> item.path())
                .containsExactly("/api/vault/groups", "/api/projects");
        assertThat(overview.recentRequests()).extracting(item -> item.id()).containsExactly(3L, 2L, 1L);
    }

    @Test
    void inspectionCandidatesKeepFastFailuresAheadOfSlowSuccessfulRequests() {
        when(mapper.listOwnedSince(eq("member-a"), any(LocalDateTime.class))).thenReturn(List.of(
                metric(3, "GET", "/api/slow-success", 200, 3_000),
                metric(2, "GET", "/api/fast-failure", 503, 80),
                metric(1, "GET", "/api/fast-failure", 503, 100)));

        var overview = service.inspectionOverview("member-a", 24, 1);

        assertThat(overview.totalRequests()).isEqualTo(3);
        assertThat(overview.failedRequests()).isEqualTo(2);
        assertThat(overview.endpoints()).singleElement().satisfies(endpoint -> {
            assertThat(endpoint.path()).isEqualTo("/api/fast-failure");
            assertThat(endpoint.failedCount()).isEqualTo(2);
            assertThat(endpoint.averageDurationMs()).isEqualTo(90);
        });
    }

    @Test
    void returnsRealEmptyStateAndPurgesThirtyDayRetention() {
        when(mapper.listOwnedSince(eq("member-a"), any(LocalDateTime.class))).thenReturn(List.of());
        var overview = service.overview("member-a", 24);
        assertThat(overview.totalRequests()).isZero();
        assertThat(overview.endpoints()).isEmpty();
        assertThat(overview.recentRequests()).isEmpty();

        service.purgeExpired();
        verify(mapper).deleteBefore(any(LocalDateTime.class));
    }

    @Test
    void clearsOnlyTheCurrentMembersHistoryAndReturnsDeletedCount() {
        when(mapper.deleteOwned("member-a")).thenReturn(7);

        assertThat(service.clear("member-a")).isEqualTo(7);

        verify(mapper).deleteOwned("member-a");
    }

    private HttpRequestMetricEntity metric(long id, String method, String path, Integer status, int duration) {
        HttpRequestMetricEntity metric = new HttpRequestMetricEntity();
        metric.setId(id);
        metric.setMemberId("member-a");
        metric.setMethod(method);
        metric.setRequestPath(path);
        metric.setStatusCode(status);
        metric.setDurationMs(duration);
        metric.setNetworkType("4g");
        metric.setRetryAttempt(0);
        metric.setStartedAt(LocalDateTime.now().minusSeconds(id));
        return metric;
    }
}
