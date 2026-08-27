package cn.wayuwa.wao.dto;

/** Runner 从本机标准系统接口读取的有界资源快照；不包含进程列表、用户名或文件路径。 */
public record RunnerSystemMetricsReport(
        Integer cpuCores,
        Double cpuUsagePercent,
        Long memoryTotalBytes,
        Long memoryAvailableBytes,
        Long diskTotalBytes,
        Long diskFreeBytes,
        String diskRoot,
        String collectedAt) {
}
