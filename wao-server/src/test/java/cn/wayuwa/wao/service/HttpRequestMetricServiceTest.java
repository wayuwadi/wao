package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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

        ArgumentCaptor<HttpRequestMetricEntity> captured = ArgumentCaptor.forClass(HttpRequestMetricEntity.class);
        verify(mapper).insert(captured.capture());
        assertThat(captured.getValue()).satisfies(metric -> {
            assertThat(metric.getMemberId()).isEqualTo("member-a");
            assertThat(metric.getMethod()).isEqualTo("GET");
            assertThat(metric.getRequestPath()).isEqualTo("/api/projects");
            assertThat(metric.getDurationMs()).isEqualTo(1_234);
            assertThat(metric.getRetryAttempt()).isEqualTo(1);
        });
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
    void systemOverviewAggregatesAcrossMembersWithoutReturningIdentity() {
        HttpRequestMetricEntity first = metric(2, "GET", "/api/tasks", 200, 2_000);
        HttpRequestMetricEntity second = metric(1, "GET", "/api/tasks", 500, 4_000);
        second.setMemberId("member-b");
        when(mapper.listSince(any(LocalDateTime.class))).thenReturn(List.of(first, second));

        var overview = service.systemOverview(24);

        assertThat(overview.totalRequests()).isEqualTo(2);
        assertThat(overview.failedRequests()).isEqualTo(1);
        assertThat(overview.endpoints()).singleElement().satisfies(endpoint -> {
            assertThat(endpoint.path()).isEqualTo("/api/tasks");
            assertThat(endpoint.requestCount()).isEqualTo(2);
        });
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
