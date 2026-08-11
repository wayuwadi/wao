package cn.wayuwa.wao.agent.service;

import cn.wayuwa.wao.agent.domain.HotspotEntity;
import cn.wayuwa.wao.agent.hotspot.HotspotSource;
import cn.wayuwa.wao.agent.hotspot.HotspotSourceStatus;
import cn.wayuwa.wao.agent.hotspot.RawHotspot;
import cn.wayuwa.wao.agent.mapper.HotspotMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 热点采集与查询。
 *
 * <p>热点是全局数据，不按工作区隔离 —— 微博热搜对谁都一样，按工作区各存一份没有意义。
 * 工作区差异体现在推荐环节（{@link TopicService} 按画像筛选）。
 *
 * <p>每个来源独立采集、独立失败：一个站点改版或限流不能让整次刷新失败，
 * 失败原因记在 {@link HotspotSourceStatus} 里供页面展示。
 */
@Service
public class HotspotService {
    private static final Logger log = LoggerFactory.getLogger(HotspotService.class);
    /** 超过这个时间没再出现在榜单上的热点会被清掉，避免推荐里混进过期话题。 */
    private static final int RETENTION_HOURS = 48;

    private final List<HotspotSource> sources;
    private final HotspotMapper hotspotMapper;
    private final Map<String, HotspotSourceStatus> lastRun = new ConcurrentHashMap<>();

    public HotspotService(List<HotspotSource> sources, HotspotMapper hotspotMapper) {
        this.sources = sources;
        this.hotspotMapper = hotspotMapper;
    }

    /** 采集全部可用来源，返回每个来源的结果。任一来源异常只影响它自己。 */
    public List<HotspotSourceStatus> refreshAll() {
        LocalDateTime now = LocalDateTime.now();
        List<HotspotSourceStatus> results = new ArrayList<>();
        for (HotspotSource source : sources) {
            if (!source.available()) {
                results.add(new HotspotSourceStatus(source.code(), source.label(), false, source.note(),
                        0, null, now));
                continue;
            }
            try {
                int count = ingest(source, now);
                results.add(new HotspotSourceStatus(source.code(), source.label(), true, source.note(),
                        count, null, now));
            } catch (Exception ex) {
                log.warn("采集热点失败 source={} : {}", source.code(), ex.toString());
                results.add(new HotspotSourceStatus(source.code(), source.label(), true, source.note(),
                        0, describe(ex), now));
            }
        }
        results.forEach(status -> lastRun.put(status.code(), status));
        prune(now);
        return results;
    }

    /** 各来源最近一次采集状态；从未采集过的来源也会列出。 */
    public List<HotspotSourceStatus> statuses() {
        return sources.stream()
                .map(source -> lastRun.getOrDefault(source.code(),
                        new HotspotSourceStatus(source.code(), source.label(), source.available(),
                                source.note(), 0, null, null)))
                .toList();
    }

    /** 按名次取最新热点；每个来源各取前 limitPerSource 条，混合后按名次排。 */
    public List<HotspotEntity> recent(int limitPerSource) {
        LocalDateTime since = LocalDateTime.now().minusHours(RETENTION_HOURS);
        List<HotspotEntity> all = hotspotMapper.selectList(Wrappers.<HotspotEntity>lambdaQuery()
                .ge(HotspotEntity::getLastSeenAt, since)
                .orderByAsc(HotspotEntity::getRankNo));
        Map<String, List<HotspotEntity>> bySource = new LinkedHashMap<>();
        for (HotspotEntity item : all) {
            bySource.computeIfAbsent(item.getSource(), key -> new ArrayList<>()).add(item);
        }
        List<HotspotEntity> picked = new ArrayList<>();
        bySource.values().forEach(list -> picked.addAll(list.stream().limit(limitPerSource).toList()));
        picked.sort(Comparator.comparingInt(HotspotEntity::getRankNo));
        return picked;
    }

    /** 按标识读取热点；未传标识时返回 {@code null}。 */
    public HotspotEntity find(String hotspotId) {
        return hotspotId == null ? null : hotspotMapper.selectById(hotspotId);
    }

    /** 每 30 分钟自动采集一次；榜单本身变化没那么快，再密集只是徒增请求。 */
    @Scheduled(initialDelayString = "${wao-agent.hotspot.initial-delay-ms:20000}",
            fixedDelayString = "${wao-agent.hotspot.refresh-delay-ms:1800000}")
    public void scheduledRefresh() {
        refreshAll();
    }

    private int ingest(HotspotSource source, LocalDateTime now) {
        List<RawHotspot> fetched = source.fetch();
        int count = 0;
        for (RawHotspot raw : fetched) {
            if (raw.title() == null || raw.title().isBlank()) continue;
            count += upsert(source.code(), raw, now) ? 1 : 0;
        }
        return count;
    }

    /** 同一条热点重复出现时只更新名次热度和时间，保留 firstSeenAt 以便看出它火了多久。 */
    private boolean upsert(String sourceCode, RawHotspot raw, LocalDateTime now) {
        String externalId = trim(raw.externalId() == null ? raw.title() : raw.externalId(), 128);
        int updated = hotspotMapper.update(null, Wrappers.<HotspotEntity>lambdaUpdate()
                .eq(HotspotEntity::getSource, sourceCode)
                .eq(HotspotEntity::getExternalId, externalId)
                .set(HotspotEntity::getTitle, trim(raw.title(), 500))
                .set(HotspotEntity::getSummary, trim(raw.summary(), 1000))
                .set(HotspotEntity::getUrl, trim(raw.url(), 1000))
                .set(HotspotEntity::getHeat, raw.heat())
                .set(HotspotEntity::getRankNo, raw.rank())
                .set(HotspotEntity::getLastSeenAt, now));
        if (updated > 0) return true;

        HotspotEntity entity = new HotspotEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setSource(sourceCode);
        entity.setExternalId(externalId);
        entity.setTitle(trim(raw.title(), 500));
        entity.setSummary(trim(raw.summary(), 1000));
        entity.setUrl(trim(raw.url(), 1000));
        entity.setHeat(raw.heat());
        entity.setRankNo(raw.rank());
        entity.setFirstSeenAt(now);
        entity.setLastSeenAt(now);
        try {
            hotspotMapper.insert(entity);
            return true;
        } catch (DuplicateKeyException ignored) {
            // 两台节点同时采集时可能撞唯一键，视为对方已写入。
            return false;
        }
    }

    private void prune(LocalDateTime now) {
        hotspotMapper.delete(Wrappers.<HotspotEntity>lambdaQuery()
                .lt(HotspotEntity::getLastSeenAt, now.minusHours(RETENTION_HOURS)));
    }

    private String describe(Exception ex) {
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return message.length() > 200 ? message.substring(0, 200) : message;
    }

    private String trim(String value, int max) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }
}
