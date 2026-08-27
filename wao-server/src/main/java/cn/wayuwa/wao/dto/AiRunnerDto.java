package cn.wayuwa.wao.dto;

import java.util.List;

/** 执行节点的安全展示信息，不包含节点令牌。 */
public record AiRunnerDto(
        String id,
        String name,
        String platform,
        List<String> engines,
        int maxConcurrency,
        Integer configuredMaxConcurrency,
        int activeRuns,
        boolean online,
        String lastSeenAt,
        String wakeMac,
        String wakeHost,
        int wakePort,
        boolean wakeConfigured,
        String lastWakeRequestedAt,
        /** Runner 脚本是否与服务端打包的最新版一致；旧版 Runner 未上报哈希时为 null。 */
        Boolean scriptUpToDate,
        /** 后台已下发脚本更新、该电脑还没完成自更新重启。 */
        boolean updateRequested,
        boolean memoryCollectionEnabled,
        boolean memoryCollectionDue,
        String lastMemoryCollectionAttemptAt,
        String lastMemoryCollectedAt,
        String lastMemoryCollectionError,
        Integer cpuCores,
        Double cpuUsagePercent,
        Long memoryTotalBytes,
        Long memoryAvailableBytes,
        Long diskTotalBytes,
        Long diskFreeBytes,
        String diskRoot,
        String systemMetricsCollectedAt) {
    /** 兼容不构造机器资源字段的内部通知与旧单元测试。 */
    public AiRunnerDto(String id, String name, String platform, List<String> engines,
            int maxConcurrency, Integer configuredMaxConcurrency, int activeRuns, boolean online,
            String lastSeenAt, String wakeMac, String wakeHost, int wakePort, boolean wakeConfigured,
            String lastWakeRequestedAt, Boolean scriptUpToDate, boolean updateRequested,
            boolean memoryCollectionEnabled, boolean memoryCollectionDue,
            String lastMemoryCollectionAttemptAt, String lastMemoryCollectedAt,
            String lastMemoryCollectionError) {
        this(id, name, platform, engines, maxConcurrency, configuredMaxConcurrency, activeRuns, online,
                lastSeenAt, wakeMac, wakeHost, wakePort, wakeConfigured, lastWakeRequestedAt,
                scriptUpToDate, updateRequested, memoryCollectionEnabled, memoryCollectionDue,
                lastMemoryCollectionAttemptAt, lastMemoryCollectedAt, lastMemoryCollectionError,
                null, null, null, null, null, null, null, null);
    }
}
