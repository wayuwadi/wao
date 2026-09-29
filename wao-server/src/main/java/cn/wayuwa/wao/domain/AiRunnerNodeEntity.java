package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;

/** 一台安装了 WAO 执行节点和至少一种 AI CLI（Codex / Claude Code）的电脑。 */
@TableName("ai_runner_node")
public class AiRunnerNodeEntity {
    @TableId
    private String id;
    private String name;
    private String platform;
    private String engines;
    private Integer maxConcurrency;
    private Integer adminMaxConcurrency;
    private String wakeMac;
    private String wakeHost;
    private Integer wakePort;
    private LocalDateTime lastWakeRequestedAt;
    private Boolean onlineState;
    private String tokenHash;
    /** Runner 心跳自报的脚本 SHA-256，用于判断是否落后于服务端打包的最新版。 */
    private String scriptHash;
    /** 后台一键下发脚本更新的时间；节点上报最新哈希后自动清除。 */
    private LocalDateTime updateRequestedAt;
    /** Runner 标准系统接口采集的资源快照；不保存进程、用户名和具体目录。 */
    private Integer cpuCores;
    private Double cpuUsagePercent;
    private Long memoryTotalBytes;
    private Long memoryAvailableBytes;
    private Long diskTotalBytes;
    private Long diskFreeBytes;
    private String diskRoot;
    private LocalDateTime systemMetricsCollectedAt;
    /** Runner 最近一次心跳上报的已脱敏日志尾部，不包含完整历史日志。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String logTail;
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime logUpdatedAt;
    private LocalDateTime lastSeenAt;
    private LocalDateTime createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getEngines() { return engines; }
    public void setEngines(String engines) { this.engines = engines; }
    public Integer getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(Integer maxConcurrency) { this.maxConcurrency = maxConcurrency; }
    public Integer getAdminMaxConcurrency() { return adminMaxConcurrency; }
    public void setAdminMaxConcurrency(Integer adminMaxConcurrency) { this.adminMaxConcurrency = adminMaxConcurrency; }
    public String getWakeMac() { return wakeMac; }
    public void setWakeMac(String wakeMac) { this.wakeMac = wakeMac; }
    public String getWakeHost() { return wakeHost; }
    public void setWakeHost(String wakeHost) { this.wakeHost = wakeHost; }
    public Integer getWakePort() { return wakePort; }
    public void setWakePort(Integer wakePort) { this.wakePort = wakePort; }
    public LocalDateTime getLastWakeRequestedAt() { return lastWakeRequestedAt; }
    public void setLastWakeRequestedAt(LocalDateTime lastWakeRequestedAt) { this.lastWakeRequestedAt = lastWakeRequestedAt; }
    public Boolean getOnlineState() { return onlineState; }
    public void setOnlineState(Boolean onlineState) { this.onlineState = onlineState; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public String getScriptHash() { return scriptHash; }
    public void setScriptHash(String scriptHash) { this.scriptHash = scriptHash; }
    public LocalDateTime getUpdateRequestedAt() { return updateRequestedAt; }
    public void setUpdateRequestedAt(LocalDateTime updateRequestedAt) { this.updateRequestedAt = updateRequestedAt; }
    public Integer getCpuCores() { return cpuCores; }
    public void setCpuCores(Integer value) { cpuCores = value; }
    public Double getCpuUsagePercent() { return cpuUsagePercent; }
    public void setCpuUsagePercent(Double value) { cpuUsagePercent = value; }
    public Long getMemoryTotalBytes() { return memoryTotalBytes; }
    public void setMemoryTotalBytes(Long value) { memoryTotalBytes = value; }
    public Long getMemoryAvailableBytes() { return memoryAvailableBytes; }
    public void setMemoryAvailableBytes(Long value) { memoryAvailableBytes = value; }
    public Long getDiskTotalBytes() { return diskTotalBytes; }
    public void setDiskTotalBytes(Long value) { diskTotalBytes = value; }
    public Long getDiskFreeBytes() { return diskFreeBytes; }
    public void setDiskFreeBytes(Long value) { diskFreeBytes = value; }
    public String getDiskRoot() { return diskRoot; }
    public void setDiskRoot(String value) { diskRoot = value; }
    public LocalDateTime getSystemMetricsCollectedAt() { return systemMetricsCollectedAt; }
    public void setSystemMetricsCollectedAt(LocalDateTime value) { systemMetricsCollectedAt = value; }
    public String getLogTail() { return logTail; }
    public void setLogTail(String logTail) { this.logTail = logTail; }
    public LocalDateTime getLogUpdatedAt() { return logUpdatedAt; }
    public void setLogUpdatedAt(LocalDateTime logUpdatedAt) { this.logUpdatedAt = logUpdatedAt; }
    public LocalDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(LocalDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
