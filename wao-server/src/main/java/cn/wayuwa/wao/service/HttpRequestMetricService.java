package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.HttpRequestMetricEntity;
import cn.wayuwa.wao.dto.RequestMetricEndpointDto;
import cn.wayuwa.wao.dto.RequestMetricInput;
import cn.wayuwa.wao.dto.RequestMetricItemDto;
import cn.wayuwa.wao.dto.RequestMetricOverviewDto;
import cn.wayuwa.wao.mapper.HttpRequestMetricMapper;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 保存并聚合浏览器端到端 HTTP 请求体验数据。 */
@Service
public class HttpRequestMetricService {

    static final int SLOW_REQUEST_MS = 1_000;
    static final int RETENTION_DAYS = 30;
    private static final int RECENT_LIMIT = 100;
    private static final int ENDPOINT_LIMIT = 50;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE");

    private final HttpRequestMetricMapper mapper;

    public HttpRequestMetricService(HttpRequestMetricMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 把当前成员的一批浏览器实测指标写入数据库。
     *
     * @param memberId 服务端会话解析出的成员 id
     * @param inputs 最多 100 条的实测指标
     * @return 实际写入条数
     */
    @Transactional
    public int record(String memberId, List<RequestMetricInput> inputs) {
        OffsetDateTime now = OffsetDateTime.now(ZONE);
        for (RequestMetricInput input : inputs) {
            mapper.insert(entity(memberId, input, now));
        }
        return inputs.size();
    }

    /**
     * 聚合当前成员指定小时窗口内的全部真实记录。
     *
     * @param memberId 当前成员 id
     * @param hours 1～168 小时
     * @return 总览、接口聚合和最近 100 条明细
     */
    public RequestMetricOverviewDto overview(String memberId, int hours) {
        if (hours < 1 || hours > 168) throw new BadRequestException("hours 必须在 1 到 168 之间");
        LocalDateTime now = LocalDateTime.now(ZONE);
        List<HttpRequestMetricEntity> metrics = mapper.listOwnedSince(memberId, now.minusHours(hours));
        List<RequestMetricEndpointDto> endpoints = endpointStats(metrics).stream()
                .sorted(Comparator.comparingInt(RequestMetricEndpointDto::averageDurationMs).reversed()
                        .thenComparing(RequestMetricEndpointDto::path))
                .limit(ENDPOINT_LIMIT)
                .toList();
        List<RequestMetricItemDto> recent = metrics.stream().limit(RECENT_LIMIT).map(this::item).toList();
        return new RequestMetricOverviewDto(hours, now, metrics.size(), average(metrics), percentile95(metrics),
                (int) metrics.stream().filter(this::slow).count(),
                (int) metrics.stream().filter(this::failed).count(), endpoints, recent);
    }

    /** 每六小时清理超过 30 天的全局指标；删除不影响业务数据。 */
    @Scheduled(fixedDelay = 21_600_000, initialDelay = 180_000)
    public void purgeExpired() {
        mapper.deleteBefore(LocalDateTime.now(ZONE).minusDays(RETENTION_DAYS));
    }

    private HttpRequestMetricEntity entity(String memberId, RequestMetricInput input, OffsetDateTime now) {
        String method = input.method().trim().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) throw new BadRequestException("HTTP 方法不受支持");
        String path = path(input.path());
        OffsetDateTime started = input.startedAt();
        if (started.isBefore(now.minusDays(RETENTION_DAYS)) || started.isAfter(now.plusMinutes(5))) {
            throw new BadRequestException("请求开始时间超出允许范围");
        }
        HttpRequestMetricEntity entity = new HttpRequestMetricEntity();
        entity.setMemberId(memberId);
        entity.setMethod(method);
        entity.setRequestPath(path);
        entity.setStatusCode(input.statusCode());
        entity.setDurationMs(input.durationMs());
        entity.setNetworkType(text(input.networkType()));
        entity.setRetryAttempt(input.retryAttempt() == null ? 0 : input.retryAttempt());
        entity.setStartedAt(LocalDateTime.ofInstant(started.toInstant(), ZONE));
        entity.setCreatedAt(LocalDateTime.now(ZONE));
        return entity;
    }

    private String path(String raw) {
        String value = raw.trim();
        try {
            URI uri = URI.create(value);
            if (uri.isAbsolute() || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !value.startsWith("/api/") || value.startsWith("/api/request-metrics")) {
                throw new IllegalArgumentException();
            }
            return value;
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("path 必须是已去除查询参数的 WAO API 路径");
        }
    }

    private List<RequestMetricEndpointDto> endpointStats(List<HttpRequestMetricEntity> metrics) {
        Map<String, List<HttpRequestMetricEntity>> grouped = new LinkedHashMap<>();
        for (HttpRequestMetricEntity metric : metrics) {
            grouped.computeIfAbsent(metric.getMethod() + " " + metric.getRequestPath(), ignored -> new ArrayList<>())
                    .add(metric);
        }
        return grouped.values().stream().map(group -> new RequestMetricEndpointDto(
                group.get(0).getMethod(), group.get(0).getRequestPath(), group.size(), average(group),
                percentile95(group), (int) group.stream().filter(this::slow).count(),
                (int) group.stream().filter(this::failed).count())).toList();
    }

    private int average(List<HttpRequestMetricEntity> metrics) {
        if (metrics.isEmpty()) return 0;
        return (int) Math.round(metrics.stream().mapToInt(HttpRequestMetricEntity::getDurationMs).average().orElse(0));
    }

    private int percentile95(List<HttpRequestMetricEntity> metrics) {
        if (metrics.isEmpty()) return 0;
        List<Integer> sorted = metrics.stream().map(HttpRequestMetricEntity::getDurationMs).sorted().toList();
        int index = Math.max(0, (int) Math.ceil(sorted.size() * 0.95) - 1);
        return sorted.get(index);
    }

    private boolean slow(HttpRequestMetricEntity metric) {
        return metric.getDurationMs() >= SLOW_REQUEST_MS;
    }

    private boolean failed(HttpRequestMetricEntity metric) {
        return metric.getStatusCode() == null || metric.getStatusCode() >= 400;
    }

    private RequestMetricItemDto item(HttpRequestMetricEntity metric) {
        return new RequestMetricItemDto(metric.getId(), metric.getMethod(), metric.getRequestPath(),
                metric.getStatusCode(), metric.getDurationMs(), metric.getNetworkType(),
                metric.getRetryAttempt(), metric.getStartedAt());
    }

    private String text(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}
